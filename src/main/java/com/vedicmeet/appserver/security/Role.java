package com.vedicmeet.appserver.security;

/**
 * Mirrors the Node Roles constant (utils/_constant.js):
 *   consultant: 'consultant', user: 'user', admin: 'admin', subAdmin: 'sub-admin'
 * The string VALUES must match exactly — they are embedded in every JWT.
 */
public final class Role {
    public static final String CONSULTANT = "consultant";
    public static final String USER = "user";
    public static final String ADMIN = "admin";
    public static final String SUB_ADMIN = "sub-admin";

    private Role() {}
}
