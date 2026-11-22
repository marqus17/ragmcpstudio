package com.ragmcp.studio.domain.port;

import com.ragmcp.studio.domain.model.McpServerStatus;

public interface McpServerControllerPort {

    McpServerStatus start(int port, String dbPath);

    McpServerStatus stop();

    McpServerStatus restart(int port, String dbPath);

    McpServerStatus status();
}
