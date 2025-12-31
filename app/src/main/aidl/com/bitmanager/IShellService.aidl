package com.bitmanager;

interface IShellService {
    String exec(in String[] cmd) = 1;
    void destroy() = 16777114;
}
