package com.bitmanager;

import android.os.IBinder;
import android.os.RemoteException;

public class ShellService extends IShellService.Stub {
    
    @Override
    public String exec(String[] cmd) throws RemoteException {
        try {
            Process process = Runtime.getRuntime().exec(cmd);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            throw new RemoteException(e.getMessage());
        }
    }
    
    @Override
    public void destroy() {
        System.exit(0);
    }
}
