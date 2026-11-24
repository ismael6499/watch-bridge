package com.nitsutech.watchbridge.engine;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Base64;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.PrefsManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NotificationParser {

    private static final Pattern PHOTO_COUNT_PATTERN = Pattern.compile(
            "(?:^|\\D)(\\d+)\\s*(?:photos?|fotos?|images?|im[aá]genes?|stickers?)(?:\\b|$)",
            Pattern.CASE_INSENSITIVE
    );

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
        parsed.isChronometerCall = parsed.isIncomingCall && extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false);
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
                List<Bitmap> styleBitmaps = new ArrayList<>();
                for (int i = messages.size() - 1; i >= 0 && styleBitmaps.size() < 4; i--) {
                    NotificationCompat.MessagingStyle.Message m = messages.get(i);
                    if (m.getDataUri() != null) {
                        String mime = m.getDataMimeType();
                        if (mime != null && !mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
                            continue;
                        }
                        Bitmap bmp = decodeBitmapFromUri(context, m.getDataUri(), 360);
                        if (bmp != null) {
                            styleBitmaps.add(0, bmp);
                        }
                    }
                }
                if (!styleBitmaps.isEmpty()) {
                    saveBitmapsToMediaFields(context, styleBitmaps, parsed);
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

        if (TextUtils.isEmpty(senderName) || senderName.equalsIgnoreCase(parsed.appLabel)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Object callPersonObj = extras.getParcelable("android.callPerson");
                if (callPersonObj instanceof android.app.Person) {
                    CharSequence cpName = ((android.app.Person) callPersonObj).getName();
                    if (!TextUtils.isEmpty(cpName)) {
                        senderName = cpName.toString();
                        convTitle = senderName;
                    }
                }
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
        if (TextUtils.isEmpty(parsed.mediaBase64)
                && (AppDatabase.PKG_WHATSAPP.equals(pkg) || AppDatabase.PKG_WHATSAPP_BUSINESS.equals(pkg))
                && parseExpectedPhotoCount(text) > 0) {
            tryAttachRecentWhatsAppMedia(context, parsed);
        }
        if (parsed.senderAvatar == null) {
            Icon largeIcon = n.getLargeIcon();
            if (largeIcon != null) {
                try {
                    parsed.senderAvatar = drawableToScaledBitmap(largeIcon.loadDrawable(context), 128);
                } catch (Exception ignored) {
                }
            }
        }
        if (parsed.senderAvatar != null) {
            parsed.avatarBase64 = encodeBitmapWebpBase64(scaleBitmap(parsed.senderAvatar, 96), 70);
        }

        parsed.appColor = n.color;
        if (parsed.appColor == 0) {
            if (AppDatabase.PKG_TEAMS.equals(pkg)) parsed.appColor = 0xFF7F85F5;
            else if (AppDatabase.PKG_WHATSAPP.equals(pkg) || AppDatabase.PKG_WHATSAPP_BUSINESS.equals(pkg)) parsed.appColor = 0xFF21C063;
        }
        parsed.smallIconBase64 = extractNormalizedSmallIconBase64(context, pkg, n);

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
        if (n.extras == null) return false;
        String template = n.extras.getString(Notification.EXTRA_TEMPLATE, "");
        if (template != null && template.contains("CallStyle")) return true;
        return n.extras.getParcelable("android.answerIntent") != null
                || n.extras.getParcelable("android.declineIntent") != null
                || n.extras.getParcelable("android.hangUpIntent") != null;
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
        String lower = text.toLowerCase(Locale.ROOT).trim();
        return lower.contains("🎤")
                || lower.contains("🎙")
                || lower.contains("voice message")
                || lower.contains("mensaje de voz")
                || lower.contains("nota de voz")
                || lower.matches(".*\\baudio\\s*\\(\\d+:\\d+\\).*")
                || lower.startsWith("audio ")
                || lower.matches("^\\d{1,2}:\\d{2}$");
    }

    public static boolean hasVoiceFolderAccess(Context context, PrefsManager prefs) {
        return canReadDirectStorage(context) || !TextUtils.isEmpty(prefs.getVoiceNotesTreeUri());
    }

    public static boolean canReadDirectStorage(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static byte[] readVoiceNoteBytes(Context context, String storedUri, long msgTimeMs,
                                            PrefsManager prefs, int maxBytes) {
        byte[] direct = readBytesFromAnyUri(context, storedUri, maxBytes);
        if (direct != null && direct.length > 0) {
            return direct;
        }
        String freshUri = findLatestVoiceNoteUri(context, prefs, msgTimeMs);
        if (!TextUtils.isEmpty(freshUri) && !freshUri.equals(storedUri)) {
            return readBytesFromAnyUri(context, freshUri, maxBytes);
        }
        return null;
    }

    private static byte[] readBytesFromAnyUri(Context context, String uriStr, int maxBytes) {
        if (TextUtils.isEmpty(uriStr)) return null;
        try {
            if (uriStr.startsWith("/") || uriStr.startsWith("file://")) {
                File f = new File(uriStr.startsWith("file://") ? Uri.parse(uriStr).getPath() : uriStr);
                if (!f.exists() || !f.canRead()) return null;
                try (InputStream is = new FileInputStream(f)) {
                    return readStreamLimited(is, maxBytes);
                }
            }
            try (InputStream is = context.getContentResolver().openInputStream(Uri.parse(uriStr))) {
                return readStreamLimited(is, maxBytes);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readStreamLimited(InputStream is, int maxBytes) throws Exception {
        if (is == null) return null;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int read;
        while ((read = is.read(buf)) != -1) {
            if (total + read > maxBytes) break;
            baos.write(buf, 0, read);
            total += read;
        }
        return baos.toByteArray();
    }

    public static String findLatestVoiceNoteUri(Context context, PrefsManager prefs, long msgTimeMs) {
        if (canReadDirectStorage(context)) {
            File bestFile = findLatestDirectWhatsAppAudio(msgTimeMs);
            if (bestFile != null) {
                return Uri.fromFile(bestFile).toString();
            }
        }
        String treeUriStr = prefs.getVoiceNotesTreeUri();
        if (TextUtils.isEmpty(treeUriStr)) return "";
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr));
            if (root == null || !root.exists()) return "";
            DocumentFile newest = scanNewestAudio(root, msgTimeMs, 0);
            return newest != null ? newest.getUri().toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static File findLatestDirectWhatsAppAudio(long targetTime) {
        File ext = Environment.getExternalStorageDirectory();
        if (ext == null) return null;
        String[] relPaths = new String[]{
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio",
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes",
                "WhatsApp/Media/WhatsApp Voice Notes",
                "WhatsApp/Media/WhatsApp Audio"
        };
        File best = null;
        long bestDiff = Long.MAX_VALUE;
        long maxAgeWindowMs = 15 * 60_000L;

        for (String rel : relPaths) {
            File dir = new File(ext, rel);
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] entries = dir.listFiles();
            if (entries == null || entries.length == 0) continue;

            List<File> subDirs = new ArrayList<>();
            for (File f : entries) {
                if (f.isDirectory()) {
                    String dn = f.getName().toLowerCase(Locale.ROOT);
                    if (!"sent".equals(dn) && !"private".equals(dn)) {
                        subDirs.add(f);
                    }
                } else if (isSupportedAudioName(f.getName())) {
                    long mod = f.lastModified();
                    long diff = targetTime > 0 ? Math.abs(targetTime - mod) : Math.max(0L, System.currentTimeMillis() - mod);
                    if (diff < maxAgeWindowMs && diff < bestDiff) {
                        best = f;
                        bestDiff = diff;
                    }
                }
            }
            // Sort YYYYWW subfolders descending so current week is checked first!
            Collections.sort(subDirs, (a, b) -> b.getName().compareToIgnoreCase(a.getName()));
            int limit = Math.min(subDirs.size(), 5);
            for (int i = 0; i < limit; i++) {
                File[] children = subDirs.get(i).listFiles();
                if (children == null) continue;
                for (File f : children) {
                    if (f.isFile() && isSupportedAudioName(f.getName())) {
                        long mod = f.lastModified();
                        long diff = targetTime > 0 ? Math.abs(targetTime - mod) : Math.max(0L, System.currentTimeMillis() - mod);
                        if (diff < maxAgeWindowMs && diff < bestDiff) {
                            best = f;
                            bestDiff = diff;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static DocumentFile scanNewestAudio(DocumentFile dir, long targetTime, int depth) {
        if (depth > 2) return null;
        DocumentFile best = null;
        long bestDiff = Long.MAX_VALUE;
        DocumentFile[] files = dir.listFiles();
        if (files == null) return null;

        List<DocumentFile> subDirs = new ArrayList<>();
        long maxAgeWindowMs = 15 * 60_000L;
        for (DocumentFile f : files) {
            if (f.isDirectory()) {
                String dirName = f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT);
                if (!"sent".equals(dirName) && !"private".equals(dirName)) {
                    subDirs.add(f);
                }
            } else if (isSupportedAudioName(f.getName())) {
                long mod = f.lastModified();
                long diff = targetTime > 0 ? Math.abs(targetTime - mod) : Math.max(0L, System.currentTimeMillis() - mod);
                if (diff < maxAgeWindowMs && diff < bestDiff) {
                    best = f;
                    bestDiff = diff;
                }
            }
        }

        // Check newest YYYYWW folders first so we don't scan 250 historical folders
        Collections.sort(subDirs, (a, b) -> {
            String na = a.getName() == null ? "" : a.getName();
            String nb = b.getName() == null ? "" : b.getName();
            return nb.compareToIgnoreCase(na);
        });
        int limit = Math.min(subDirs.size(), 6);
        for (int i = 0; i < limit; i++) {
            DocumentFile sub = scanNewestAudio(subDirs.get(i), targetTime, depth + 1);
            if (sub != null) {
                long mod = sub.lastModified();
                long diff = targetTime > 0 ? Math.abs(targetTime - mod) : Math.max(0L, System.currentTimeMillis() - mod);
                if (diff < bestDiff) {
                    best = sub;
                    bestDiff = diff;
                }
            }
        }
        return best;
    }

    private static boolean isSupportedAudioName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".opus") || lower.endsWith(".ogg") || lower.endsWith(".m4a")
                || lower.endsWith(".aac") || lower.endsWith(".mp3");
    }

    public static int parseExpectedPhotoCount(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        Matcher m = PHOTO_COUNT_PATTERN.matcher(text);
        if (m.find()) {
            try {
                int count = Integer.parseInt(m.group(1));
                return Math.max(1, Math.min(4, count));
            } catch (NumberFormatException ignored) {
                return 4;
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("📷") || lower.contains("📸")
                || lower.contains("photo") || lower.contains("foto")
                || lower.contains("image") || lower.contains("imagen")
                || lower.contains("sticker") || lower.contains("gif")) {
            return 1;
        }
        return 0;
    }

    public static boolean tryAttachRecentWhatsAppMedia(Context context, ParsedMessage parsed) {
        if (context == null || parsed == null || !canReadDirectStorage(context)) {
            return false;
        }
        int expected = Math.max(1, Math.min(4, parseExpectedPhotoCount(parsed.messageText)));
        File ext = Environment.getExternalStorageDirectory();
        if (ext == null) return false;

        String[] relPaths = new String[]{
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images",
                "WhatsApp/Media/WhatsApp Images",
                "WhatsApp Business/Media/WhatsApp Business Images"
        };
        long targetTime = parsed.timestamp > 0 ? parsed.timestamp : System.currentTimeMillis();
        long maxAgeWindowMs = 90_000L;
        List<File> candidates = new ArrayList<>();

        for (String rel : relPaths) {
            File dir = new File(ext, rel);
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || !isSupportedImageName(f.getName())) continue;
                if (Math.abs(targetTime - f.lastModified()) <= maxAgeWindowMs) {
                    candidates.add(f);
                }
            }
        }
        if (candidates.isEmpty()) return false;

        candidates.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int limit = Math.min(expected, candidates.size());
        List<File> selected = new ArrayList<>(candidates.subList(0, limit));
        if (selected.size() > 1) {
            selected.sort(Comparator.comparingLong(File::lastModified));
        }

        List<Bitmap> bitmaps = new ArrayList<>();
        for (File f : selected) {
            Bitmap bmp = decodeSampledFile(f, 320);
            if (bmp != null) {
                bitmaps.add(bmp);
            }
        }
        if (bitmaps.isEmpty()) return false;

        saveBitmapsToMediaFields(context, bitmaps, parsed);
        return !TextUtils.isEmpty(parsed.mediaBase64);
    }

    private static boolean isSupportedImageName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private static Bitmap decodeBitmapFromUri(Context context, Uri uri, int maxDim) {
        try {
            ContentResolver cr = context.getContentResolver();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = cr.openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                opts.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim);
            }
            try (InputStream is = cr.openInputStream(uri)) {
                if (is == null) return null;
                Bitmap raw = BitmapFactory.decodeStream(is, null, opts);
                return scaleBitmap(raw, maxDim);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Bitmap decodeSampledFile(File f, int reqMaxDim) {
        if (f == null || !f.exists() || !f.canRead()) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, reqMaxDim);
            opts.inJustDecodeBounds = false;
            Bitmap raw = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            return scaleBitmap(raw, reqMaxDim);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int calculateInSampleSize(int width, int height, int reqMaxDim) {
        int inSampleSize = 1;
        while ((width / inSampleSize) > reqMaxDim * 2 || (height / inSampleSize) > reqMaxDim * 2) {
            inSampleSize *= 2;
        }
        return inSampleSize;
    }

    private static void extractBitmapFromExtras(Context context, Bundle extras, ParsedMessage parsed) {
        try {
            Parcelable pic = extras.getParcelable(Notification.EXTRA_PICTURE);
            if (pic instanceof Bitmap) {
                saveBitmapToMediaFields(context, (Bitmap) pic, parsed);
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Parcelable picIcon = extras.getParcelable(Notification.EXTRA_PICTURE_ICON);
                if (picIcon instanceof Icon) {
                    Drawable d = ((Icon) picIcon).loadDrawable(context);
                    Bitmap bmp = drawableToScaledBitmap(d, 480);
                    if (bmp != null) {
                        saveBitmapToMediaFields(context, bmp, parsed);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveBitmapToMediaFields(Context context, Bitmap raw, ParsedMessage parsed) {
        if (raw == null) return;
        saveBitmapsToMediaFields(context, Collections.singletonList(raw), parsed);
    }

    private static void saveBitmapsToMediaFields(Context context, List<Bitmap> bitmaps, ParsedMessage parsed) {
        saveBitmapsToMediaFields(context, bitmaps, bitmaps == null ? 0 : bitmaps.size(), parsed);
    }

    public static void saveBitmapsToMediaFields(Context context, List<Bitmap> bitmaps, int totalCount, ParsedMessage parsed) {
        if (bitmaps == null || bitmaps.isEmpty()) return;
        try {
            Bitmap phonePreview;
            Bitmap watchThumb;
            if (bitmaps.size() == 1) {
                Bitmap single = bitmaps.get(0);
                phonePreview = scaleBitmap(single, 480);
                watchThumb = scaleBitmap(single, 260);
            } else {
                Bitmap collage = composePreviewBitmap(bitmaps, totalCount);
                phonePreview = collage;
                watchThumb = collage;
            }

            File dir = new File(context.getCacheDir(), "proxy_media");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "media_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                phonePreview.compress(Bitmap.CompressFormat.PNG, 90, fos);
            }
            parsed.mediaPath = out.getAbsolutePath();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            watchThumb.compress(Bitmap.CompressFormat.WEBP, 60, baos);
            parsed.mediaBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
        }
    }

    private static Bitmap composePreviewBitmap(List<Bitmap> bitmaps, int totalCount) {
        int count = Math.min(bitmaps.size(), 4);
        int canvasW = 320;
        int canvasH = count == 2 ? 160 : 320;
        Bitmap out = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF121212);

        if (count == 2) {
            drawCenterCropped(canvas, bitmaps.get(0), new Rect(0, 0, 158, 160));
            drawCenterCropped(canvas, bitmaps.get(1), new Rect(162, 0, 320, 160));
        } else {
            drawCenterCropped(canvas, bitmaps.get(0), new Rect(0, 0, 158, 158));
            drawCenterCropped(canvas, bitmaps.get(1), new Rect(162, 0, 320, 158));
            drawCenterCropped(canvas, bitmaps.get(2), new Rect(0, 162, 158, 320));
            if (count >= 4) {
                Rect fourth = new Rect(162, 162, 320, 320);
                drawCenterCropped(canvas, bitmaps.get(3), fourth);
                if (totalCount > 4) {
                    Paint overlay = new Paint();
                    overlay.setColor(0x99000000);
                    canvas.drawRect(fourth, overlay);

                    Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    textPaint.setColor(0xFFFFFFFF);
                    textPaint.setTextSize(48f);
                    textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    Paint.FontMetrics fm = textPaint.getFontMetrics();
                    float centerX = (fourth.left + fourth.right) / 2f;
                    float centerY = (fourth.top + fourth.bottom) / 2f - (fm.ascent + fm.descent) / 2f;
                    canvas.drawText("+" + (totalCount - 4), centerX, centerY, textPaint);
                }
            }
        }
        return out;
    }

    private static void drawCenterCropped(Canvas canvas, Bitmap src, Rect dst) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return;
        float srcAspect = (float) src.getWidth() / src.getHeight();
        float dstAspect = (float) dst.width() / dst.height();
        Rect srcRect;
        if (srcAspect > dstAspect) {
            int cropW = Math.max(1, Math.round(src.getHeight() * dstAspect));
            int left = (src.getWidth() - cropW) / 2;
            srcRect = new Rect(left, 0, left + cropW, src.getHeight());
        } else {
            int cropH = Math.max(1, Math.round(src.getWidth() / dstAspect));
            int top = (src.getHeight() - cropH) / 2;
            srcRect = new Rect(0, top, src.getWidth(), top + cropH);
        }
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(src, srcRect, dst, paint);
    }

    private static void extractActions(Notification n, ParsedMessage parsed) {
        if (parsed.isIncomingCall && n.extras != null) {
            Parcelable ans = n.extras.getParcelable("android.answerIntent");
            if (ans instanceof PendingIntent) {
                parsed.answerCallIntent = (PendingIntent) ans;
            }
            Parcelable dec = n.extras.getParcelable("android.declineIntent");
            if (!(dec instanceof PendingIntent)) {
                dec = n.extras.getParcelable("android.hangUpIntent");
            }
            if (dec instanceof PendingIntent) {
                parsed.declineCallIntent = (PendingIntent) dec;
            }
        }
        if (n.actions == null) return;
        for (Notification.Action action : n.actions) {
            if (action == null) continue;
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs != null && remoteInputs.length > 0 && parsed.replyAction == null) {
                parsed.replyAction = action;
                continue;
            }
            String actionTitle = action.title != null ? action.title.toString().toLowerCase(Locale.ROOT) : "";
            if (parsed.isIncomingCall) {
                if (parsed.answerCallIntent == null && action.actionIntent != null
                        && (actionTitle.contains("answer") || actionTitle.contains("accept")
                        || actionTitle.contains("pick up") || actionTitle.contains("atender")
                        || actionTitle.contains("aceptar") || actionTitle.contains("contestar")
                        || actionTitle.contains("responder") || actionTitle.contains("join")
                        || actionTitle.contains("unirse"))) {
                    parsed.answerCallIntent = action.actionIntent;
                    continue;
                }
                if (parsed.declineCallIntent == null && action.actionIntent != null
                        && (actionTitle.contains("decline") || actionTitle.contains("reject")
                        || actionTitle.contains("hang up") || actionTitle.contains("end")
                        || actionTitle.contains("dismiss") || actionTitle.contains("rechazar")
                        || actionTitle.contains("colgar") || actionTitle.contains("finalizar"))) {
                    parsed.declineCallIntent = action.actionIntent;
                    continue;
                }
            }
            if (parsed.markReadAction == null && (actionTitle.contains("read") || actionTitle.contains("leído") || actionTitle.contains("leido"))) {
                parsed.markReadAction = action;
                continue;
            }
            if (!parsed.isIncomingCall && action.actionIntent != null && action.title != null) {
                String cleanAction = action.title.toString().trim();
                if (!cleanAction.isEmpty() && parsed.extraActions.size() < 3) {
                    parsed.extraActions.add(action);
                    parsed.extraActionTitles.add(cleanAction);
                }
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

    private static String encodeBitmapWebpBase64(Bitmap bmp, int quality) {
        if (bmp == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.WEBP, quality, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractNormalizedSmallIconBase64(Context context, String pkg, Notification n) {
        try {
            Icon small = n.getSmallIcon();
            if (small == null) return "";
            Drawable d = small.loadDrawable(context);
            if (d == null && !TextUtils.isEmpty(pkg)) {
                Context pkgCtx = context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY);
                d = small.loadDrawable(pkgCtx);
            }
            if (d == null) return "";
            Bitmap bmp = drawableToScaledBitmap(d, 64);
            if (bmp == null) return "";
            Bitmap normalized = normalizeSmallIconMask(bmp);
            if (normalized == null) return "";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            normalized.compress(Bitmap.CompressFormat.PNG, 100, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static Bitmap normalizeSmallIconMask(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        int opaqueCount = 0;
        int visibleCount = 0;
        for (int i = 0; i < pixels.length; i++) {
            int alpha = (pixels[i] >>> 24) & 0xFF;
            if (alpha > 10) visibleCount++;
            if (alpha > 240) opaqueCount++;
            pixels[i] = (alpha << 24) | 0x00FFFFFF;
        }
        // If >92% of pixels are completely opaque (non-transparent square), skip custom small icon
        if (visibleCount == 0 || opaqueCount > (pixels.length * 0.92f)) {
            return null;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    private static String charSeqToString(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
