package io.putdotio.android.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The probe's one file: a small .torrent other apps may read through a drag's grant. */
public final class DragProbeProvider extends ContentProvider {
    static final String NAME = "Harbor film.torrent";
    static final byte[] TORRENT = ("d8:announce19:udp://tracker.local4:infod6:lengthi1024e4:name11:Harbor film"
            + "12:piece lengthi16384e6:pieces20:01234567890123456789ee").getBytes(StandardCharsets.US_ASCII);

    /**
     * Outside the app's own authority prefix: put.io refuses to read a torrent from any provider named
     * after its package, which the test package's name would otherwise share.
     */
    static final String AUTHORITY = "io.putdotio.probe.files";

    public static Uri torrentUri(Context context) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(NAME).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {NAME, (long) TORRENT.length});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/x-bittorrent";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = new File(getContext().getCacheDir(), "probe.torrent");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(TORRENT);
        } catch (IOException error) {
            throw new FileNotFoundException(error.toString());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
