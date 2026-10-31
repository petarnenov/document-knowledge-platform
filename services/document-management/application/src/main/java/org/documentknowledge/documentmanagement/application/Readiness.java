package org.documentknowledge.documentmanagement.application;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Process readiness and current dependency health; never an authorization decision. */
public final class Readiness {
    private final AtomicBoolean acceptingWork=new AtomicBoolean(true);
    private final BooleanSupplier dependencies;
    public Readiness(){this(()->true);}
    public Readiness(BooleanSupplier dependencies){this.dependencies=Objects.requireNonNull(dependencies);}
    public boolean isAcceptingWork(){try{return acceptingWork.get() && dependencies.getAsBoolean();}catch(RuntimeException unavailable){return false;}}
    public void markUnavailable(){acceptingWork.set(false);}
    public void markAvailable(){acceptingWork.set(true);}
}
