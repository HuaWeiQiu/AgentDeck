package com.agentdeck.app.data.host.lab;

interface IShellCommandService {
    /**
     * 在 Shizuku server（shell UID）内执行命令，返回 JSON：
     * {"exit": int, "output": String} 或 {"timeout": true}。
     */
    String exec(String command);

    void destroy();
}
