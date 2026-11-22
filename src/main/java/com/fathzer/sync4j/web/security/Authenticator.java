package com.fathzer.sync4j.web.security;

import java.io.IOException;

/**
 * Checks the credentials of a user.
 * <br>For instance, an application can delegate it to another service (a photo server, an LDAP, ...).
 */
@FunctionalInterface
public interface Authenticator {
    /**
     * Authenticates a user.
     * @param user the user name
     * @param password the password
     * @return the user identifier that is stored in the session
     * @throws AuthenticationFailedException if the credentials are rejected
     * @throws IOException if the authentication can't be performed
     */
    String authenticate(String user, String password) throws IOException;
}
