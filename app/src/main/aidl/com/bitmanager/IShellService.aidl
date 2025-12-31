package com.bitmanager;

interface IShellService {
    String exec(in String[] cmd);
    void destroy() = 16777114; // Destroy method transaction code
}
