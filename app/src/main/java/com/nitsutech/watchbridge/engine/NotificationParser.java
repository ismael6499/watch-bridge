package com.nitsutech.watchbridge.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Base64;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.documentfile.provider.DocumentFile;

import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.PrefsManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

public class NotificationParser {

    public static ParsedMessage parse(Context context, StatusBarNotification sbn,
                                      NotificationListenerService.RankingMap rankingMap, PrefsManager prefs) {
        if (sbn == null || sbn.getNotification() == null) return null;
        String pkg = sbn.getPackageName();
        if (TextUtils.isEmpty(pkg) || context.getPackageName().equals(pkg)) return null;

        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return null;
        }
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 && !isCallNotification(n)) {
            return null;
        }

        Bundle extras = n.extras;
        if (extras == null) return null;

        String title = charSeqToString(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = charSeqToString(extras.getCharSequence(Notification.EXTRA_TEXT));
        String bigText = charSeqToString(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (bigText.length() > text.length()) {
            text = bigText;
        }
        String convTitle = charSeqToString(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));

        if (isSystemNoise(pkg, title, text) && !isCallNotification(n)) {
            return null;
        }

        ParsedMessage parsed = new ParsedMessage();
        parsed.notificationKey = sbn.getKey();
        parsed.packageName = pkg;
        parsed.appLabel = resolveAppLabel(context, pkg);
        parsed.timestamp = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        parsed.isIncomingCall = isCallNotification(n);
        parsed.isSourceSilent = !parsed.isIncomingCall && isSourceNotificationSilent(sbn, n, rankingMap);

        // Extract MessagingStyle details when available
        NotificationCompat.MessagingStyle style = NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(n);

        boolean isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        String senderName = title;

        if (style != null) {
            if (style.isGroupConversation()) {
                isGroup = true;
            }
            if (!TextUtils.isEmpty(style.getConversationTitle())) {
                convTitle = style.getConversationTitle().toString();
            }
            List<NotificationCompat.MessagingStyle.Message> messages = style.getMessages();
            if (!messages.isEmpty()) {
                NotificationCompat.MessagingStyle.Message lastMsg = messages.get(messages.size() - 1);
                if (lastMsg.getText() != null && !TextUtils.isEmpty(lastMsg.getText().toString())) {
                    text = lastMsg.getText().toString();
                }
                Person p = lastMsg.getPerson();
                if (p != null && p.getName() != null && !TextUtils.isEmpty(p.getName().toString())) {
                    senderName = p.getName().toString();
                    if (p.getIcon() != null) {
                        Drawable d = p.getIcon().loadDrawable(context);
                        parsed.senderAvatar = drawableToScaledBitmap(d, 128);
                    }
                }
                if (lastMsg.getDataUri() != null) {
                    extractMediaFromUri(context, lastMsg.getDataUri(), parsed);
                }
            }
        }

        // Heuristic fallback for group chats formatted as "Group: Sender" or when EXTRA_CONVERSATION_TITLE is populated
        if (!isGroup && !TextUtils.isEmpty(convTitle) && !convTitle.equalsIgnoreCase(senderName)) {
            isGroup = true;
        }
        if (!isGroup && title.contains(": ") && (AppDatabase.PKG_WHATSAPP.equals(pkg) || AppDatabase.PKG_WHATSAPP_BUSINESS.equals(pkg))) {
            int idx = title.indexOf(": ");
            if (idx > 0 && idx < title.length() - 2) {
                isGroup = true;
                convTitle = title.substring(0, idx).trim();
                senderName = title.substring(idx + 2).trim();
            }
        }

        if (TextUtils.isEmpty(convTitle)) {
            convTitle = !TextUtils.isEmpty(title) ? title : parsed.appLabel;
        }
        if (TextUtils.isEmpty(senderName)) {
            senderName = convTitle;
        }
        if (TextUtils.isEmpty(text)) {
            if (parsed.isIncomingCall) {
                text = "Incoming call";
            } else if (!TextUtils.isEmpty(parsed.mediaPath)) {
                text = "[Image / Sticker]";
            } else {
                return null;
            }
        }

        parsed.isGroup = isGroup;
        parsed.conversationTitle = convTitle;
        parsed.senderName = senderName;
        parsed.messageText = text;

        String rawKey = pkg + "|" + (isGroup ? "grp|" + convTitle : "dm|" + senderName);
        parsed.conversationKey = rawKey.toLowerCase(Locale.ROOT).trim();

        // Check Deleted Message notice (Idea #4 Anti-Delete Shield)
        parsed.isDeletedNotice = isDeletedText(text);

        // Extract BigPicture / Picture / LargeIcon
        if (TextUtils.isEmpty(parsed.mediaPath)) {
            extractBitmapFromExtras(context, extras, parsed);
        }
        if (parsed.senderAvatar == null) {
            Icon largeIcon = n.getLargeIcon();
            if (largeIcon != null) {
                parsed.senderAvatar = drawableToScaledBitmap(largeIcon.loadDrawable(context), 128);
            }
        }

        // Detect Voice Note & attempt to resolve latest .opus file from SAF tree
        if (isVoiceNoteText(text)) {
            parsed.hasVoiceNote = true;
            parsed.voiceNoteUri = findLatestVoiceNoteUri(context, prefs, parsed.timestamp);
        }

        // Extract Reply & Mark Read actions FIRST so we accurately classify Chat vs Event/Calendar
        extractActions(n, parsed);
        parsed.canReply = parsed.replyAction != null;
        parsed.canMarkRead = parsed.markReadAction != null;

        boolean isEventCategory = Notification.CATEGORY_EVENT.equals(n.category)
                || Notification.CATEGORY_REMINDER.equals(n.category)
                || Notification.CATEGORY_STATUS.equals(n.category)
                || Notification.CATEGORY_SYSTEM.equals(n.category);

        if (parsed.isIncomingCall) {
            parsed.notificationKind = com.nitsutech.watchbridge.wear.WearProtocol.KIND_CALL;
            parsed.canReply = false;
        } else if (!isEventCategory && (parsed.canReply || style != null || Notification.CATEGORY_MESSAGE.equals(n.category))) {
            parsed.notificationKind = com.nitsutech.watchbridge.wear.WearProtocol.KIND_CHAT;
        } else {
            // Non-chat notifications (e.g. Teams calendar meeting updates, reminders, system alerts)
            parsed.notificationKind = com.nitsutech.watchbridge.wear.WearProtocol.KIND_EVENT;
            parsed.canReply = false;
            parsed.hasVoiceNote = false;
        }

        return parsed;
    }

    private static boolean isSourceNotificationSilent(
            StatusBarNotification sbn,
            Notification n,
            NotificationListenerService.RankingMap rankingMap
    ) {
        if (rankingMap != null && sbn.getKey() != null) {
            NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
            if (rankingMap.getRanking(sbn.getKey(), ranking)) {
                if (ranking.isAmbient()) {
                    return true;
                }
                int imp = ranking.getImportance();
                if (imp != NotificationManager.IMPORTANCE_UNSPECIFIED
                        && imp <= NotificationManager.IMPORTANCE_LOW) {
                    return true;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel channel = ranking.getChannel();
                    if (channel != null) {
                        if (channel.getImportance() <= NotificationManager.IMPORTANCE_LOW) {
                            return true;
                        }
                        if (channel.getSound() == null && !channel.shouldVibrate()) {
                            return true;
                        }
                        return false;
                    }
                }
            }
        }
        boolean hasSound = n.sound != null || (n.defaults & Notification.DEFAULT_SOUND) != 0;
        boolean hasVibrate = (n.vibrate != null && n.vibrate.length > 0)
                || (n.defaults & Notification.DEFAULT_VIBRATE) != 0;
        return !hasSound && !hasVibrate;
    }

    private static boolean isCallNotification(Notification n) {
        if (Notification.CATEGORY_CALL.equals(n.category)) return true;
        String template = n.extras != null ? n.extras.getString(Notification.EXTRA_TEMPLATE, "") : "";
        return template != null && template.contains("CallStyle");
    }

    private static boolean isSystemNoise(String pkg, String title, String text) {
        String lowerText = (text == null ? "" : text).toLowerCase(Locale.ROOT);
        String lowerTitle = (title == null ? "" : title).toLowerCase(Locale.ROOT);
        if (lowerText.contains("checking for new messages")
                || lowerText.contains("buscando mensajes nuevos")
                || lowerText.contains("whatsapp web is currently active")
                || lowerText.contains("whatsapp web está activo")
                || lowerText.contains("backing up messages")
                || lowerText.contains("copia de seguridad")
                || lowerTitle.equals("whatsapp") && lowerText.matches(".*\\d+ messages from \\d+ chats.*")
                || lowerTitle.equals("whatsapp") && lowerText.matches(".*\\d+ mensajes de \\d+ chats.*")) {
            return true;
        }
        if (AppDatabase.PKG_TEAMS.equals(pkg) && (lowerText.contains("syncing") || lowerText.contains("sincronizando"))) {
            return true;
        }
        return TextUtils.isEmpty(title) && TextUtils.isEmpty(text);
    }

    private static boolean isDeletedText(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String lower = text.toLowerCase(Locale.ROOT).trim();
        return lower.equals("this message was deleted")
                || lower.equals("you deleted this message")
                || lower.equals("este mensaje fue eliminado")
                || lower.equals("eliminaste este mensaje")
                || lower.contains("message was deleted")
                || lower.contains("mensaje fue eliminado");
    }

    private static boolean isVoiceNoteText(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("🎤")
                || lower.contains("voice message")
                || lower.contains("mensaje de voz")
                || lower.contains("audio (0:")
                || lower.contains("audio (1:")
                || lower.startsWith("audio ")
                || lower.contains("nota de voz");
    }

    private static String findLatestVoiceNoteUri(Context context, PrefsManager prefs, long msgTimeMs) {
        String treeUriStr = prefs.getVoiceNotesTreeUri();
        if (TextUtils.isEmpty(treeUriStr)) return "";
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr));
            if (root == null || !root.exists()) return "";
            DocumentFile newest = scanNewestAudio(root, msgTimeMs);
            return newest != null ? newest.getUri().toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static DocumentFile scanNewestAudio(DocumentFile dir, long targetTime) {
        DocumentFile best = null;
        long bestTime = 0L;
        DocumentFile[] files = dir.listFiles();
        if (files == null) return null;
        // Look inside subfolders sorted by latest modified (WhatsApp groups voice notes in YYYYWW folders)
        for (DocumentFile f : files) {
            if (f.isDirectory()) {
                String dirName = f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT);
                // Skip Sent (outgoing) folder so we never pick our own sent voice notes
                if ("sent".equals(dirName)) {
                    continue;
                }
                if ("private".equals(dirName)
                        || f.lastModified() >= targetTime - 7 * 24 * 3600 * 1000L
                        || f.lastModified() == 0) {
                    DocumentFile sub = scanNewestAudio(f, targetTime);
                    if (sub != null && (best == null
                            || Math.abs(targetTime - sub.lastModified()) < Math.abs(targetTime - bestTime))) {
                        best = sub;
                        bestTime = sub.lastModified();
                    }
                }
            } else {
                String name = f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".opus") || name.endsWith(".ogg") || name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".mp3")) {
                    long mod = f.lastModified();
                    if (Math.abs(targetTime - mod) < 120_000L
                            && (best == null || Math.abs(targetTime - mod) < Math.abs(targetTime - bestTime))) {
                        best = f;
                        bestTime = mod;
                    }
                }
            }
        }
        return best;
    }

    private static void extractMediaFromUri(Context context, Uri uri, ParsedMessage parsed) {
        try {
            ContentResolver cr = context.getContentResolver();
            try (InputStream is = cr.openInputStream(uri)) {
                if (is == null) return;
                Bitmap raw = BitmapFactory.decodeStream(is);
                if (raw != null) {
                    saveBitmapToMediaFields(context, raw, parsed);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void extractBitmapFromExtras(Context context, Bundle extras, ParsedMessage parsed) {
        try {
            Parcelable pic = extras.getParcelable(Notification.EXTRA_PICTURE);
            if (pic instanceof Bitmap) {
                saveBitmapToMediaFields(context, (Bitmap) pic, parsed);
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveBitmapToMediaFields(Context context, Bitmap raw, ParsedMessage parsed) {
        try {
            Bitmap scaled = scaleBitmap(raw, 480);
            File dir = new File(context.getCacheDir(), "proxy_media");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "media_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                scaled.compress(Bitmap.CompressFormat.PNG, 90, fos);
            }
            parsed.mediaPath = out.getAbsolutePath();

            Bitmap thumb = scaleBitmap(raw, 180);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            thumb.compress(Bitmap.CompressFormat.WEBP, 65, baos);
            parsed.mediaBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
        }
    }

    private static void extractActions(Notification n, ParsedMessage parsed) {
        if (n.actions == null) return;
        for (Notification.Action action : n.actions) {
            if (action == null) continue;
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs != null && remoteInputs.length > 0 && parsed.replyAction == null) {
                parsed.replyAction = action;
                continue;
            }
            String actionTitle = action.title != null ? action.title.toString().toLowerCase(Locale.ROOT) : "";
            if (parsed.markReadAction == null && (actionTitle.contains("read") || actionTitle.contains("leído") || actionTitle.contains("leido"))) {
                parsed.markReadAction = action;
            }
        }
    }

    private static String resolveAppLabel(Context context, String pkg) {
        if (AppDatabase.PKG_TEAMS.equals(pkg)) return "Microsoft Teams";
        if (AppDatabase.PKG_WHATSAPP.equals(pkg)) return "WhatsApp";
        if (AppDatabase.PKG_WHATSAPP_BUSINESS.equals(pkg)) return "WhatsApp Business";
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }

    private static Bitmap drawableToScaledBitmap(Drawable drawable, int maxDim) {
        if (drawable == null) return null;
        Bitmap src;
        if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
            src = ((BitmapDrawable) drawable).getBitmap();
        } else {
            int w = Math.max(drawable.getIntrinsicWidth(), 64);
            int h = Math.max(drawable.getIntrinsicHeight(), 64);
            src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(src);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        }
        return scaleBitmap(src, maxDim);
    }

    private static Bitmap scaleBitmap(Bitmap src, int maxDim) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxDim && h <= maxDim) return src;
        float ratio = Math.min((float) maxDim / w, (float) maxDim / h);
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private static String charSeqToString(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
