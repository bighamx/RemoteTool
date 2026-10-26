package com.chuckiehelper.mobile.phonecontrol;
import android.os.ParcelFileDescriptor;
interface IPhoneShell {
    String execute(in String[] argv, int timeoutMs) = 0;
    byte[] screenshot() = 1;
    int uid() = 2;
    String installApk(in ParcelFileDescriptor source, long length, int timeoutMs) = 3;
    void destroy() = 16777114;
}
