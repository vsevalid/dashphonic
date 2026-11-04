package com.syu.ipc;

interface IModuleCallback {
    void update(int updateCode, in int[] ints, in float[] flts, in String[] strs);
}
