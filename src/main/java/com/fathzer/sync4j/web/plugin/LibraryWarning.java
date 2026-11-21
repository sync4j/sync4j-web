package com.fathzer.sync4j.web.plugin;

/**
 * A problem that a plugin found in a library (see {@link SyncPlugin#check(com.fathzer.sync4j.config.Library)}).
 * @param plugin the id of the plugin
 * @param message the message displayed to the user
 */
public record LibraryWarning(String plugin, Message message) {}
