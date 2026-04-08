package com.universal.saf.plugin; // 建议使用此通用包名

import android.webkit.MimeTypeMap;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;

/**
 * 通用 SAF 注入插件
 * 注入后可自动识别宿主应用包名并暴露其 /data/data/ 目录
 */
public class SAFProvider extends DocumentsProvider {

    private static final String ALL_MIME_TYPES = "*/*";

    // --- 动态获取宿主信息的方法 ---

    /**
     * 自动获取当前宿主应用的根数据目录
     * 注入后 getApplicationInfo().dataDir 会自动指向宿主私有路径
     */
    private File getBaseDir() {
        return new File(getContext().getApplicationInfo().dataDir);
    }

    /**
     * 动态获取宿主应用的名称（Label）
     * 用于在文件管理器左侧栏显示真实的 App 名字而非硬编码
     */
    private String getAppLabel() {
        try {
            return getContext().getPackageManager().getApplicationLabel(
                    getContext().getApplicationInfo()).toString();
        } catch (Exception e) {
            return "App Private Data";
        }
    }

    // --- 标准列定义 ---

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    private static String getDocIdForFile(File file) {
        return file.getAbsolutePath();
    }

    private static File getFileForDocId(String docId) throws FileNotFoundException {
        final File f = new File(docId);
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath() + " not found");
        return f;
    }


    @Override
    public Cursor queryRoots(String[] projection) {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        File baseDir = getBaseDir(); // 动态获取宿主私有目录
        String appLabel = getAppLabel(); // 动态获取应用名

        final MatrixCursor.RowBuilder row = result.newRow();
        // Root ID 使用根目录绝对路径
        row.add(Root.COLUMN_ROOT_ID, getDocIdForFile(baseDir));
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(baseDir));
        row.add(Root.COLUMN_SUMMARY, "Storage: " + baseDir.getAbsolutePath());
        
        // 赋予权限：支持创建、搜索、显示子目录
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, appLabel); 
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES);
        row.add(Root.COLUMN_AVAILABLE_BYTES, baseDir.getFreeSpace());
        row.add(Root.COLUMN_ICON, android.R.drawable.ic_dialog_info);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(parentDocumentId);
        File[] files = parent.listFiles();
        if (files != null) {
            for (File file : files) {
                includeFile(result, null, file);
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, file.length());
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = getFileForDocId(parentDocumentId);
        File newFile = new File(parent, displayName);
        
        // 处理同名文件冲突
        int noConflictId = 2;
        while (newFile.exists()) {
            newFile = new File(parent, displayName + " (" + (noConflictId++) + ")");
        }
        
        try {
            boolean succeeded;
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                succeeded = newFile.mkdir();
            } else {
                succeeded = newFile.createNewFile();
            }
            if (!succeeded) {
                throw new FileNotFoundException("Failed to create: " + newFile.getPath());
            }
        } catch (IOException e) {
            throw new FileNotFoundException("IO Error: " + newFile.getPath());
        }
        return getDocIdForFile(newFile);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (!file.delete()) {
            throw new FileNotFoundException("Failed to delete: " + documentId);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        
        final int lastDot = file.getName().lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = file.getName().substring(lastDot + 1).toLowerCase();
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId);
    }

    private void includeFile(MatrixCursor result, String docId, File file) throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
            flags |= Document.FLAG_SUPPORTS_DELETE;
        }

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, getDocumentType(docId));
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        
        
        
        // 使用 ic_dialog_info (文件夹) 和 ic_menu_edit (文件)
row.add(Document.COLUMN_ICON, file.isDirectory() ? android.R.drawable.ic_dialog_info : android.R.drawable.ic_menu_edit);

       // row.add(Document.COLUMN_ICON, file.isDirectory() ? android.R.drawable.ic_menu_archive : android.R.drawable.ic_menu_edit);
    }
}
