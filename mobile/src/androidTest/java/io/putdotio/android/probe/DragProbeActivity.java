package io.putdotio.android.probe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Another app beside put.io in split screen, in its own package and process: it offers a magnet link
 * and a .torrent file to drag into put.io, and takes files dragged out of put.io, reporting what it
 * could read and when. Written against the framework only, because the test package's own process
 * does not load the app's libraries.
 */
public final class DragProbeActivity extends Activity {
    public static final String ACTION_RECEIVED = "io.putdotio.android.probe.RECEIVED";
    public static final String ACTION_FINISH = "io.putdotio.android.probe.FINISH";
    public static final String EXTRA_REPLY_PACKAGE = "replyPackage";
    public static final String MAGNET =
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Harbor%20film";

    private TextView result;
    private ImageView preview;
    private View dropArea;
    private String replyPackage;

    private final BroadcastReceiver finisher = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        replyPackage = getIntent().getStringExtra(EXTRA_REPLY_PACKAGE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x22, 0x26, 0x2E));
        int padding = dp(24);
        root.setPadding(padding, padding * 2, padding, padding);

        root.addView(text("Another app", 24, Color.WHITE));
        root.addView(text("Long-press a chip to drag it into put.io.", 14, Color.LTGRAY));

        TextView magnet = chip("Magnet link · Harbor film");
        magnet.setOnLongClickListener(view -> view.startDragAndDrop(
                ClipData.newPlainText("Harbor film", MAGNET),
                new View.DragShadowBuilder(view), null, View.DRAG_FLAG_GLOBAL));
        root.addView(magnet);

        TextView torrent = chip("Harbor film.torrent");
        torrent.setOnLongClickListener(view -> {
            ClipDescription description = new ClipDescription(
                    "Harbor film.torrent", new String[] {"application/x-bittorrent"});
            ClipData clip = new ClipData(description, new ClipData.Item(DragProbeProvider.torrentUri(this)));
            return view.startDragAndDrop(clip, new View.DragShadowBuilder(view), null,
                    View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ);
        });
        root.addView(torrent);

        LinearLayout drop = new LinearLayout(this);
        drop.setOrientation(LinearLayout.VERTICAL);
        drop.setGravity(Gravity.CENTER);
        drop.setPadding(padding, padding, padding, padding);
        drop.setBackground(outline(Color.rgb(0x8A, 0x93, 0xA6)));
        drop.addView(text("Drop a put.io file here", 18, Color.WHITE));
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(dp(220));
        drop.addView(preview);
        result = text("", 14, Color.LTGRAY);
        drop.addView(result);
        LinearLayout.LayoutParams dropParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        dropParams.topMargin = dp(24);
        root.addView(drop, dropParams);
        dropArea = drop;
        drop.setOnDragListener(this::onDrag);

        setContentView(root);
        registerReceiver(finisher, new IntentFilter(ACTION_FINISH), Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(finisher);
        super.onDestroy();
    }

    private boolean onDrag(View view, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getLocalState() == null;
            case DragEvent.ACTION_DRAG_ENTERED:
                dropArea.setBackground(outline(Color.rgb(0xFD, 0xCE, 0x45)));
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                dropArea.setBackground(outline(Color.rgb(0x8A, 0x93, 0xA6)));
                return true;
            case DragEvent.ACTION_DROP:
                receive(event);
                return true;
            default:
                return true;
        }
    }

    /** Reads the dropped URI as any app would, and checks the grant covers that drop and nothing more. */
    private void receive(DragEvent event) {
        ClipData clip = event.getClipData();
        Uri uri = clip.getItemAt(0).getUri();
        String label = String.valueOf(clip.getDescription().getLabel());
        boolean readBeforeRequest = canRead(uri);
        DragAndDropPermissions permissions = requestDragAndDropPermissions(event);
        new Thread(() -> {
            Intent report = new Intent(ACTION_RECEIVED).setPackage(replyPackage)
                    .putExtra("uri", String.valueOf(uri))
                    .putExtra("label", label)
                    .putExtra("readBeforeRequest", readBeforeRequest)
                    .putExtra("granted", permissions != null);
            byte[] bytes = null;
            try {
                report.putExtra("name", displayName(uri)).putExtra("type", getContentResolver().getType(uri));
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    bytes = readAll(input);
                }
                report.putExtra("size", bytes.length).putExtra("sha256", sha256(bytes));
                report.putExtra("writeWithGrant", canWrite(uri));
            } catch (Exception error) {
                report.putExtra("error", error.getClass().getName());
            } finally {
                if (permissions != null) permissions.release();
            }
            report.putExtra("readAfterRelease", canRead(uri));
            byte[] shown = bytes;
            runOnUiThread(() -> show(report, shown));
            sendBroadcast(report);
        }).start();
    }

    private void show(Intent report, byte[] bytes) {
        Bitmap image = bytes == null ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        preview.setImageBitmap(image);
        String text = report.hasExtra("error")
                ? "Could not read the drop: " + report.getStringExtra("error")
                : "Received " + report.getStringExtra("name") + " · " + report.getStringExtra("type")
                        + " · " + report.getIntExtra("size", 0) + " bytes\n"
                        + "Readable before the drop's grant: " + report.getBooleanExtra("readBeforeRequest", true) + "\n"
                        + "Writable with it: " + report.getBooleanExtra("writeWithGrant", true) + "\n"
                        + "Readable after releasing it: " + report.getBooleanExtra("readAfterRelease", true);
        result.setText(text);
    }

    private boolean canRead(Uri uri) {
        try (InputStream ignored = getContentResolver().openInputStream(uri)) {
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private boolean canWrite(Uri uri) {
        try {
            getContentResolver().openFileDescriptor(uri, "w").close();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static byte[] readAll(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private TextView chip(String label) {
        TextView view = text(label, 18, Color.BLACK);
        view.setPadding(dp(20), dp(14), dp(20), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(0xFD, 0xCE, 0x45));
        background.setCornerRadius(dp(24));
        view.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        view.setLayoutParams(params);
        return view;
    }

    private GradientDrawable outline(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setStroke(dp(3), color);
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
