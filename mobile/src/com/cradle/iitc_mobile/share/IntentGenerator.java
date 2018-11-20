package com.cradle.iitc_mobile.share;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;

import com.cradle.iitc_mobile.Log;
import com.cradle.iitc_mobile.R;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class IntentGenerator {
    private static final String EXTRA_FLAG_IS_DEFAULT = "IITCM_IS_DEFAULT";
    private static final String EXTRA_FLAG_TITLE = "IITCM_TITLE";
    private static final HashSet<ComponentName> KNOWN_COPY_HANDLERS = new HashSet<ComponentName>();

    static {
        if (KNOWN_COPY_HANDLERS.isEmpty()) {

            KNOWN_COPY_HANDLERS.add(new ComponentName(
                    "com.google.android.apps.docs",
                    "com.google.android.apps.docs.app.SendTextToClipboardActivity"));

            KNOWN_COPY_HANDLERS.add(new ComponentName(
                    "com.aokp.romcontrol",
                    "com.aokp.romcontrol.ShareToClipboard"));
        }
    }

    public static String getTitle(final Intent intent) {
        String title = "";
        if (intent.hasExtra(EXTRA_FLAG_TITLE))
            title = intent.getCharSequenceExtra(EXTRA_FLAG_TITLE).toString();

        // Samsung WiFi Direct Sharing seems to not provide a title.
        // Not directly reproducible without having a Samsung device.

        if (title == null || "".equals(title)) {
            Log.w("Intent has no title!\n"
                    + "Intent:\n" + intent.toUri(Intent.URI_INTENT_SCHEME) + "\n"
                    + "Extras:\n" + intent.getExtras().toString());
            return "unknown";
        }

        return title;
    }

    public static boolean isDefault(final Intent intent) {
        return intent.hasExtra(EXTRA_FLAG_IS_DEFAULT) && intent.getBooleanExtra(EXTRA_FLAG_IS_DEFAULT, false);
    }

    private final Context mContext;

    private final PackageManager mPackageManager;

    public IntentGenerator(final Context context) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
    }

    private boolean containsCopyIntent(final List<Intent> targets) {
        for (final Intent intent : targets) {
            for (final ComponentName handler : KNOWN_COPY_HANDLERS) {
                if (handler.equals(intent.getComponent())) return true;
            }
        }
        return false;
    }

    private ArrayList<Intent> resolveTargets(final Intent intent) {
        final String packageName = mContext.getPackageName();
        final List<ResolveInfo> activityList = mPackageManager.queryIntentActivities(intent, 0);
        final ResolveInfo defaultTarget = mPackageManager.resolveActivity(intent, 0);

        final ArrayList<Intent> list = new ArrayList<Intent>(activityList.size());

        for (final ResolveInfo resolveInfo : activityList) {
            final ActivityInfo activity = resolveInfo.activityInfo;
            final ComponentName component = new ComponentName(activity.packageName, activity.name);

            // remove IITCm from list (we only want other apps)
            if (activity.packageName.equals(packageName)) continue;

            // bug in package manager. not exported activities shouldn't even appear here
            // (usually you would have to compare the package name as well, but since we ignore our own activities,
            // this isn't necessary)
            if (!activity.exported) continue;

            final Intent targetIntent = new Intent(intent)
                    .setComponent(component)
                    .putExtra(EXTRA_FLAG_TITLE, activity.loadLabel(mPackageManager));

            if (resolveInfo.activityInfo.name.equals(defaultTarget.activityInfo.name) &&
                    resolveInfo.activityInfo.packageName.equals(defaultTarget.activityInfo.packageName)) {
                targetIntent.putExtra(EXTRA_FLAG_IS_DEFAULT, true);
            }

            list.add(targetIntent);
        }

        return list;
    }

    public void cleanup(final Intent intent) {
        intent.removeExtra(EXTRA_FLAG_IS_DEFAULT);
        intent.removeExtra(EXTRA_FLAG_TITLE);
    }

    public ArrayList<Intent> getBrowserIntents(final String title, final String url) {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return resolveTargets(intent);
    }

    public ArrayList<Intent> getGeoIntents(final String title, final String mLl, final int mZoom) {
        final Intent intent = new Intent(android.content.Intent.ACTION_VIEW,
                Uri.parse(String.format("geo:%s?z=%d", mLl, mZoom)))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final ArrayList<Intent> targets = resolveTargets(intent);

        // According to https://developer.android.com/guide/components/intents-common.html, markers can be labeled.
        // Unfortunately, only Google Maps supports this, most other apps fail
        for (final Intent target : targets) {
            final ComponentName cn = target.getComponent();
            if ("com.google.android.apps.maps".equals(cn.getPackageName())) {
                try {
                    final String encodedTitle = URLEncoder.encode(title, "UTF-8");
                    target.setData(Uri.parse(String.format("geo:0,0?q=%s%%20(%s)&z=%d", mLl, encodedTitle, mZoom)));
                } catch (final UnsupportedEncodingException e) {
                    Log.w(e);
                }
                break;
            }
        }

        return targets;
    }

    /**
     * get a list of intents capable of sharing a plain text string
     * 
     * @param title
     *            description of the shared string
     * @param text
     *            the string to be shared
     */
    public ArrayList<Intent> getShareIntents(final String title, final String text) {
        final Intent intent = new Intent(Intent.ACTION_SEND)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .putExtra(Intent.EXTRA_TEXT, text);

        final ArrayList<Intent> targets = resolveTargets(intent);

        if (!containsCopyIntent(targets)) {
            // add SendToClipboard intent in case Drive is not installed
            targets.add(new Intent(intent)
                    .setComponent(new ComponentName(mContext, SendToClipboard.class))
                    .putExtra(EXTRA_FLAG_TITLE, mContext.getString(R.string.activity_share_to_clipboard)));
        }

        return targets;
    }

    /**
     * get a list of intents capable of sharing the given content
     * 
     * @param uri
     *            URI of a file to share
     * @param type
     *            MIME type of the file
     */
    public ArrayList<Intent> getShareIntents(final Uri uri, final String type) {
        final Intent intent = new Intent(Intent.ACTION_SEND)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET)
                .setType(type)
                .putExtra(Intent.EXTRA_SUBJECT, uri.getLastPathSegment())
                .putExtra(Intent.EXTRA_STREAM, uri);

        final ArrayList<Intent> targets = resolveTargets(intent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            targets.add(new Intent(intent)
                    .setComponent(new ComponentName(mContext, SaveToFile.class))
                    .putExtra(EXTRA_FLAG_TITLE, mContext.getString(R.string.activity_save_to_file)));
        }

        return targets;
    }
}
