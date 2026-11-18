package com.hs.user.constant;

import com.hs.common.security.Permissions;

public final class PermissionConstants {

    private PermissionConstants() {
    }

    public static final class Admin {
        private Admin() {
        }

        public static final String USER_VIEW = Permissions.USER_VIEW;
        public static final String USER_CREATE = Permissions.USER_CREATE;
        public static final String USER_UPDATE = Permissions.USER_UPDATE;
        public static final String ROLE_VIEW = Permissions.ROLE_VIEW;
        public static final String ROLE_UPDATE = Permissions.ROLE_UPDATE;
        public static final String PERMISSION_VIEW = Permissions.PERMISSION_VIEW;
        public static final String PERMISSION_UPDATE = Permissions.PERMISSION_UPDATE;
    }
}

