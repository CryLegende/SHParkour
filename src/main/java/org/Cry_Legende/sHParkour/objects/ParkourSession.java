package org.Cry_Legende.sHParkour.objects;

import org.bukkit.Location;
import org.bukkit.World; // <<<< NOUVEL IMPORT

import java.util.UUID;

public class ParkourSession {

    private final UUID playerUUID;
    private final Parkour parkour;
    private long startTime;
    private int lastCheckpoint;
    private boolean playersVisible;
    private Location lastSafeLocation;
    private final String worldName; // <<<< NOUVEAU: Stocke le nom du monde du parcours

    public ParkourSession(UUID playerUUID, Parkour parkour, Location startLocation) {
        this.playerUUID = playerUUID;
        this.parkour = parkour;
        this.startTime = System.currentTimeMillis();
        this.lastCheckpoint = -1;
        this.playersVisible = true;
        this.lastSafeLocation = startLocation;
        this.worldName = startLocation.getWorld().getName(); // On enregistre le monde au début
    }

    public void resetTime() {
        this.startTime = System.currentTimeMillis();
        this.lastCheckpoint = -1;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public Parkour getParkour() {
        return parkour;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    public int getLastCheckpoint() {
        return lastCheckpoint;
    }

    public boolean arePlayersVisible() {
        return playersVisible;
    }

    public Location getLastSafeLocation() {
        return lastSafeLocation;
    }

    public String getWorldName() {
        return worldName;
    } // <<<< NOUVEAU GETTER

    public void setLastCheckpoint(int lastCheckpoint) {
        this.lastCheckpoint = lastCheckpoint;
    }

    public void setPlayersVisible(boolean playersVisible) {
        this.playersVisible = playersVisible;
    }

    public void setLastSafeLocation(Location lastSafeLocation) {
        this.lastSafeLocation = lastSafeLocation;
    }
}
