/*
 * *************************************************************************
 *  FileUtils.java
 * **************************************************************************
 *  Copyright © 2015 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.util;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import org.videolan.libvlc.util.AndroidUtil;
import org.videolan.medialibrary.media.MediaWrapper;
import org.videolan.vlc.VLCApplication;
import org.videolan.vlc.media.MediaUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileUtils {

    public final static String TAG = "VLC/FileUtils";

    /**
     * Size of the chunks that will be hashed in bytes (64 KB)
     */
    private static final int HASH_CHUNK_SIZE = 64 * 1024;

    public interface Callback {
        void onResult(boolean success);
    }

    public static String getFileNameFromPath(String path){
        if (path == null)
            return "";
        int index = path.lastIndexOf('/');
        if (index == path.length()-1) {
            path = path.substring(0, index);
            index = path.lastIndexOf('/');
        }
        if (index > -1)
            return path.substring(index+1);
        else
            return path;
    }

    public static String getParent(String path){
        if (path == null || TextUtils.equals("/", path))
            return path;
        String parentPath = path;
        if (parentPath.endsWith("/"))
            parentPath = parentPath.substring(0, parentPath.length()-1);
        int index = parentPath.lastIndexOf('/');
        if (index > 0){
            parentPath = parentPath.substring(0, index);
        } else if (index == 0)
            parentPath = "/";
        return parentPath;
    }

    /*
     * Convert file:// uri from real path to emulated FS path.
     */
    public static Uri convertLocalUri(Uri uri) {
        if (!TextUtils.equals(uri.getScheme(), "file") || !uri.getPath().startsWith("/sdcard"))
            return uri;
        String path = uri.toString();
        return Uri.parse(path.replace("/sdcard", AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY));
    }

    public static String getPathFromURI(Uri contentUri) {
        Cursor cursor = null;
        try {
            String[] proj = {MediaStore.Images.Media.DATA};
            cursor = VLCApplication.getAppContext().getContentResolver().query(contentUri, proj, null, null, null);
            if (cursor == null)
                return "";
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            return cursor.getString(column_index);
        } catch (IllegalArgumentException e) {
                return "";
        } finally {
            if (cursor != null && !cursor.isClosed())
                cursor.close();
        }
    }

    public static boolean copyAssetFolder(AssetManager assetManager, String fromAssetPath, String toPath) {
        try {
            String[] files = assetManager.list(fromAssetPath);
            if (files.length == 0)
                return false;
            new File(toPath).mkdirs();
            boolean res = true;
            for (String file : files)
                if (file.contains("."))
                    res &= copyAsset(assetManager,
                            fromAssetPath + "/" + file,
                            toPath + "/" + file);
                else
                    res &= copyAssetFolder(assetManager,
                            fromAssetPath + "/" + file,
                            toPath + "/" + file);
            return res;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean copyAsset(AssetManager assetManager,
                                     String fromAssetPath, String toPath) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assetManager.open(fromAssetPath);
            new File(toPath).createNewFile();
            out = new FileOutputStream(toPath);
            copyFile(in, out);
            out.flush();
            return true;
        } catch(Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            Util.close(in);
            Util.close(out);
        }
    }

    public static void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while((read = in.read(buffer)) != -1){
          out.write(buffer, 0, read);
        }
    }

    public static boolean copyFile(File src, File dst){
        boolean ret = true;
        if (src.isDirectory()) {
            File[] filesList = src.listFiles();
            dst.mkdirs();
            for (File file : filesList)
                ret &= copyFile(file, new File(dst, file.getName()));
        } else if (src.isFile()) {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new BufferedInputStream(new FileInputStream(src));
                out = new BufferedOutputStream(new FileOutputStream(dst));

                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                return true;
            } catch (FileNotFoundException e) {
            } catch (IOException e) {
            } finally {
                Util.close(in);
                Util.close(out);
            }
            return false;
        }
        return ret;
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static boolean deleteFile (String path){
        boolean deleted = false;
        path = Uri.decode(Strings.removeFileProtocole(path));
        //Delete from Android Medialib, for consistency with device MTP storing and other apps listing content:// media
        if (AndroidUtil.isHoneycombOrLater){
            ContentResolver cr = VLCApplication.getAppContext().getContentResolver();
            try {
                deleted = cr.delete(MediaStore.Files.getContentUri("external"),
                        MediaStore.Files.FileColumns.DATA + "=?", new String[]{path}) > 0;
            } catch (IllegalArgumentException ignored) {} // Can happen on some devices...
        }
        File file = new File(path);
        if (file.exists())
            deleted |= file.delete();
        return deleted;
    }

    public static void asyncRecursiveDelete(String path, Callback callback) {
        asyncRecursiveDelete(new File(path), callback);
    }

    public static void asyncRecursiveDelete(String path) {
        asyncRecursiveDelete(path, null);
    }

    private static void asyncRecursiveDelete(final File fileOrDirectory, final Callback callback) {
        VLCApplication.runBackground(new Runnable() {
            public void run() {
                if (!fileOrDirectory.exists() || !fileOrDirectory.canWrite())
                    return;
                boolean success = true;
                if (fileOrDirectory.isDirectory()) {
                    for (File child : fileOrDirectory.listFiles())
                        asyncRecursiveDelete(child, null);
                    success = fileOrDirectory.delete();
                } else {
                    success = deleteFile(fileOrDirectory.getPath());
                }
                if (callback != null)
                    callback.onResult(success);
            }
        });
    }

    public static boolean canSave(MediaWrapper mw){
        if (mw == null || mw.getUri() == null)
            return false;
        String scheme = mw.getUri().getScheme();
        if (TextUtils.equals(scheme, "file"))
            return false;
        return TextUtils.equals(scheme, "smb")   ||
                TextUtils.equals(scheme, "nfs")  ||
                TextUtils.equals(scheme, "ftp")  ||
                TextUtils.equals(scheme, "ftps") ||
                TextUtils.equals(scheme, "sftp");
    }

    public static boolean canWrite(Uri uri){
        if (uri == null)
            return false;
        if (TextUtils.equals("file", uri.getScheme()))
            return canWrite(uri.toString());
        if (TextUtils.equals("content", uri.getScheme()))
            return canWrite(getPathFromURI(uri));
        return false;

    }

    public static boolean canWrite(String path){
        if (TextUtils.isEmpty(path))
            return false;
        if (path.startsWith("file://"))
            path = path.substring(7);
        if (!path.startsWith("/"))
            return false;
        if (path.startsWith(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY))
            return true;
        if (AndroidUtil.isKitKatOrLater)
            return false;
        File file = new File(path);
        return (file.exists() && file.canWrite());
    }

    public static String computeHash(File file) {
        long size = file.length();
        long chunkSizeForFile = Math.min(HASH_CHUNK_SIZE, size);
        long head = 0;
        long tail = 0;
        FileInputStream fis = null;
        FileChannel fileChannel = null;
        try {
            fis = new FileInputStream(file);
            fileChannel = fis.getChannel();
            head = computeHashForChunk(fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, chunkSizeForFile));

            //Alternate way to calculate tail hash for files over 4GB.
            ByteBuffer bb = ByteBuffer.allocateDirect((int)chunkSizeForFile);
            int read;
            long position = Math.max(size - HASH_CHUNK_SIZE, 0);
            while ((read = fileChannel.read(bb, position)) > 0) {
                position += read;
            }
            bb.flip();
            tail = computeHashForChunk(bb);

            return String.format("%016x", size + head + tail);
        } catch (FileNotFoundException e1) {
            e1.printStackTrace();
            return null;
        }
        catch (IOException e) {
            e.printStackTrace();
            return null;
        }finally {
            Util.close(fileChannel);
            Util.close(fis);
        }
    }

    private static long computeHashForChunk(ByteBuffer buffer) {
        LongBuffer longBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
        long hash = 0;
        while (longBuffer.hasRemaining())
            hash += longBuffer.get();
        return hash;
    }


    public static Uri getUri(Uri data) {
        Uri uri = data;
        if (data != null && TextUtils.equals(data.getScheme(), "content")) {
            // Mail-based apps - download the stream to a temporary file and play it
            if(data.getHost().equals("com.fsck.k9.attachmentprovider")
                    || data.getHost().equals("gmail-ls")) {
                InputStream is = null;
                OutputStream os = null;
                try {
                    Cursor cursor = VLCApplication.getAppContext().getContentResolver().query(data,
                            new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
                    if (cursor != null) {
                        cursor.moveToFirst();
                        String filename = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME));
                        cursor.close();
                        Log.i(TAG, "Getting file " + filename + " from content:// URI");

                        is = VLCApplication.getAppContext().getContentResolver().openInputStream(data);
                        os = new FileOutputStream(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + "/Download/" + filename);
                        byte[] buffer = new byte[1024];
                        int bytesRead = 0;
                        while((bytesRead = is.read(buffer)) >= 0) {
                            os.write(buffer, 0, bytesRead);
                        }
                        uri = AndroidUtil.PathToUri(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + "/Download/" + filename);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Couldn't download file from mail URI");
                    return data;
                } finally {
                    Util.close(is);
                    Util.close(os);
                }
            }
            // Media or MMS URI
            else if (TextUtils.equals(data.getAuthority(), "media")){
                uri = MediaUtils.getContentMediaUri(data);
            } else {
                ParcelFileDescriptor inputPFD;
                try {
                    inputPFD = VLCApplication.getAppContext().getContentResolver().openFileDescriptor(data, "r");
                    if (AndroidUtil.isHoneycombMr1OrLater)
                        uri = AndroidUtil.LocationToUri("fd://" + inputPFD.getFd());
                    else {
                        String fdString = inputPFD.getFileDescriptor().toString();
                        uri = AndroidUtil.LocationToUri("fd://" + fdString.substring(15, fdString.length() - 1));
                    }
//                    Cursor returnCursor =
//                            getContentResolver().query(data, null, null, null, null);
//                    if (returnCursor != null) {
//                        if (returnCursor.getCount() > 0) {
//                            int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
//                            if (nameIndex > -1) {
//                                returnCursor.moveToFirst();
//                                title = returnCursor.getString(nameIndex);
//                            }
//                        }
//                        returnCursor.close();
//                    }
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "Couldn't understand the intent");
                    return data;
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission is no longer valid");
                    return data;
                }
            }
        }
        return uri;
    }

    public static float getAvailableInternalMemorySize() {
        File path = Environment.getDataDirectory();
        StatFs stat = new StatFs(path.getPath());
        long bytesAvailable = 0;
        if(AndroidUtil.isJellyBeanMR2OrLater)
            bytesAvailable = (long) stat.getBlockSizeLong() * (long) stat.getAvailableBlocksLong();
        else
            bytesAvailable = (long) stat.getBlockSize() * (long) stat.getAvailableBlocks();
        return bytesAvailable / (1024.f);
    }

    public static String md5Hash(String str) throws NoSuchAlgorithmException {
            // Create MD5 Hash
            MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            digest.update(str.getBytes());
            byte messageDigest[] = digest.digest();

            // Create Hex String
            StringBuffer hexString = new StringBuffer();
            for (int i=0; i<messageDigest.length; i++)
                hexString.append(Integer.toHexString(0xFF & messageDigest[i]));
            return hexString.toString();
    }

    public static String generateMediaUniqueName(String mediaLocation, long lastModified){
        try {
            String pattern = mediaLocation + lastModified;
            return md5Hash(pattern);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }
    public static File createMovieEncodedSubtitleDirectory(Context context, String mediaLocation, long lastModiFied){
        Uri movieUri = Uri.parse(mediaLocation);
        File subtitleDirectory = new File(context.getFilesDir()+"/"+"LinguaPlayerSubs", movieUri.getLastPathSegment());

        String uniqueName = generateMediaUniqueName(mediaLocation, lastModiFied);
        if(uniqueName== null || uniqueName.isEmpty()) {
            return null;
        }
        File movieUniqueDirectory = new File(subtitleDirectory,uniqueName);
        movieUniqueDirectory.mkdirs();
        if(movieUniqueDirectory.exists())
            return movieUniqueDirectory;
        return null;
    }
}
