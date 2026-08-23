package com.hs.user.constant;

public final class PermissionConstants {

    private PermissionConstants() {
    }

    public static final class Admin {
        private Admin() {
        }

        public static final String USER_VIEW = "USER_VIEW";
        public static final String USER_CREATE = "USER_CREATE";
        public static final String USER_UPDATE = "USER_UPDATE";
        public static final String ROLE_VIEW = "ROLE_VIEW";
        public static final String ROLE_UPDATE = "ROLE_UPDATE";
        public static final String PERMISSION_VIEW = "PERMISSION_VIEW";
        public static final String PERMISSION_UPDATE = "PERMISSION_UPDATE";
    }
}

