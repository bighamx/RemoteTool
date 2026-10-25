package com.chuckiehelper.mobile.phonecontrol;
interface IPhoneShell {
    String execute(in String[] argv, int timeoutMs) = 0;
    byte[] screenshot() = 1;
    int uid() = 2;
    void destroy() = 16777114;
}
