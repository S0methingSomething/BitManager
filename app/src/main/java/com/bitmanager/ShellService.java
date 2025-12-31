package com.bitmanager;

import java.io.*;

public class ShellService extends IShellService.Stub {
    
    @Override
    public String exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) out.append(line).append("\n");
            while ((line = er.readLine()) != null) out.append("ERR: ").append(line).append("\n");
            p.waitFor();
            out.append("EXIT: ").append(p.exitValue());
            return out.toString();
        } catch (Exception e) {
            return "EXCEPTION: " + e.getMessage();
        }
    }
    
    @Override
    public void destroy() {
        System.exit(0);
    }
}
