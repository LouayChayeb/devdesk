package io.github.louch.devdesk.core.removal;

/**
 * One thing DevDesk made for a project, which "Remove" deletes.
 *
 * @param kind what it is: process, container, network, volume, image, state or folder (also the deletion order)
 * @param id what its adapter deletes it by: a container id, a path…
 * @param what for people: "volume shop_pgdata", "the folder ~/DevDesk/shop (cloned by DevDesk)"
 * @param data whether deleting it loses data the user may want (a database volume, the cloned folder)
 */
public record Removable(Kind kind, String id, String what, boolean data) {

    /** In the order things are deleted: what runs first, then what it used, then what describes it. */
    public enum Kind {
        PROCESS,
        CONTAINER,
        NETWORK,
        VOLUME,
        IMAGE,
        STATE,
        FOLDER
    }
}
