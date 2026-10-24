package ua.teka.files;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Видає файли іншим застосункам («Відкрити в…», «Поділитися», встановлення APK) за адресами
 * content://ua.teka.files.provider/f/<шлях>. Постачальник не експортується: доступ мають лише ті
 * застосунки, яким «Тека» явно надала дозвіл на конкретний файл.
 */
public class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "ua.teka.files.provider";

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
            .encodedPath("/f" + Uri.encode(f.getAbsolutePath(), "/")).build();
    }

    List<File> roots() {
        Context c = getContext();
        List<File> r = new ArrayList<>();
        if (c == null) return r;
        for (Fs.Vol v : Fs.vols(c)) r.add(v.dir);
        r.add(c.getCacheDir());
        r.add(c.getFilesDir());
        return r;
    }

    File fileFor(Uri uri) throws FileNotFoundException {
        String p = uri.getPath();
        if (p == null || !p.startsWith("/f/")) throw new FileNotFoundException("bad uri");
        File f = new File(p.substring(2));
        for (File root : roots()) if (Names.inside(f, root)) return f;
        throw new FileNotFoundException("denied");
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        String p = uri.getPath();
        return p == null ? "application/octet-stream" : Fs.mime(new File(p).getName());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        List<String> oc = new ArrayList<>();
        List<Object> ov = new ArrayList<>();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) { oc.add(col); ov.add(f.getName()); }
            else if (OpenableColumns.SIZE.equals(col)) { oc.add(col); ov.add(f.length()); }
            else if ("_data".equals(col)) { oc.add(col); ov.add(f.getAbsolutePath()); }
            else if ("mime_type".equals(col)) { oc.add(col); ov.add(Fs.mime(f.getName())); }
            else if ("last_modified".equals(col)) { oc.add(col); ov.add(f.lastModified()); }
        }
        MatrixCursor mc = new MatrixCursor(oc.toArray(new String[0]), 1);
        mc.addRow(ov.toArray());
        return mc;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] args) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
