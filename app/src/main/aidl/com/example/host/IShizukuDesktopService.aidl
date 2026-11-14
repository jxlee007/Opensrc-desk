package com.example.host;

interface IShizukuDesktopService {
    boolean enableFreeformDesktopSupport();
    boolean launchAppOnDisplay(String packageName, String activityComponent, int displayId);
    String queryHostForegroundPackages(int displayId);
    boolean probeShellInputDisplayFlag();
    boolean injectShellTouch(int displayId, int action, int x, int y);
    boolean injectShellSwipe(int displayId, int startX, int startY, int endX, int endY, int durationMs);
    boolean injectShellKeyOrText(int displayId, int keyCode, String text);
    String moveAppBackToHost(String packageName);
    String queryDisplayStackSnapshot();
    void destroy();
}
