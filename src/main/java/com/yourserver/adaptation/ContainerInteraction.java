package com.yourserver.adaptation;

/** Ванильный secondary-use для печек/сундуков: учитываются обе руки. */
final class ContainerInteraction {
    private ContainerInteraction() { }

    static boolean bypassMenu(boolean sneaking, boolean mainHandEmpty, boolean offHandEmpty) {
        return sneaking && (!mainHandEmpty || !offHandEmpty);
    }
}
