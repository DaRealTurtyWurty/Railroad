package dev.railroadide.railroad.vcs.git.remote;

public record GitRemote(String name, String fetchUrl, String pushUrl, Protocol protocol) {
    public enum Protocol {
        HTTPS,
        SSH,
        GIT,
        FILE,
        UNKNOWN;

        public static Protocol fromUrl(String url) {
            if (url.startsWith("https://") || url.startsWith("http://")) {
                return HTTPS;
            } else if (url.startsWith("ssh://") || url.contains("@")) {
                return SSH;
            } else if (url.startsWith("git://")) {
                return GIT;
            } else if (url.startsWith("file://") || url.startsWith("/")) {
                return FILE;
            } else {
                return UNKNOWN;
            }
        }
    }
}
