package org.documentknowledge.microkernelfixtures.plugin.spi;
public class BadContract {
    public io.micronaut.context.ApplicationContext framework;
    public Object execute(Object input) { return input; }
}
