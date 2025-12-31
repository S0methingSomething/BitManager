package com.bitmanager;

interface IShellService {
    String exec(String cmd) = 1;
    void destroy() = 16777114;
}
