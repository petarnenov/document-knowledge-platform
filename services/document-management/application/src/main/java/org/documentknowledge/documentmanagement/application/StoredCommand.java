package org.documentknowledge.documentmanagement.application;
public record StoredCommand(CommandKey key,String fingerprint,int status,CommandReceipt receipt) { }
