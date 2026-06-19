package tech.sebazcrc.permadeath.util;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import tech.sebazcrc.permadeath.Main;
import tech.sebazcrc.permadeath.util.interfaces.NMSHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AsyncExplosion {

    private static final Map<Material, Float> blastResistances = new ConcurrentHashMap<>();

    public static void init(NMSHandler nmsHandler) {
        blastResistances.clear();
        for (Material mat : Material.values()) {
            if (mat.isBlock()) {
                try {
                    float resistance = nmsHandler.getBlastResistance(mat);
                    blastResistances.put(mat, resistance);
                } catch (Throwable t) {
                    blastResistances.put(mat, 0.0f);
                }
            }
        }
    }

    public static float getBlastResistance(Material material) {
        return blastResistances.getOrDefault(material, 0.0f);
    }

    public static void explode(World world, Location center, float power, boolean breakBlocks, boolean placeFire) {
        long start = System.currentTimeMillis();

        // Hilo Principal: Preparación de datos
        double maxDistance = power * 1.3 / 0.75;
        double configRadius = Main.getInstance().getConfig().getDouble("Toggles.Gatos-Supernova.Snapshot-Radius", 80.0);
        double maxSnapshotRadius = Math.min(maxDistance, configRadius);

        int centerCx = center.getBlockX() >> 4;
        int centerCz = center.getBlockZ() >> 4;
        int chunkRadius = ((int) Math.ceil(maxSnapshotRadius)) >> 4;
        chunkRadius = Math.max(1, chunkRadius + 1);

        boolean debugLog = Main.getInstance().getConfig().getBoolean("Toggles.Gatos-Supernova.Debug-Logging", false);
        boolean skipUnloaded = Main.getInstance().getConfig().getBoolean("Toggles.Gatos-Supernova.Skip-Unloaded-Chunks", true);
        double maxProcDist = Main.getInstance().getConfig().getDouble("Toggles.Gatos-Supernova.Max-Processing-Distance", 256.0);
        double maxProcDistSq = maxProcDist * maxProcDist;

        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        for (int cx = centerCx - chunkRadius; cx <= centerCx + chunkRadius; cx++) {
            for (int cz = centerCz - chunkRadius; cz <= centerCz + chunkRadius; cz++) {
                // Check if chunk coordinates are within Max-Processing-Distance
                double chunkCenterX = (cx << 4) + 8;
                double chunkCenterZ = (cz << 4) + 8;
                double dx = chunkCenterX - center.getX();
                double dz = chunkCenterZ - center.getZ();
                if (dx * dx + dz * dz > maxProcDistSq) {
                    continue;
                }

                if (world.isChunkLoaded(cx, cz)) {
                    try {
                        snapshots.put(getChunkKey(cx, cz), world.getChunkAt(cx, cz).getChunkSnapshot());
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Obtener entidades de manera segura sin cargar chunks (iterando sobre las entidades cargadas del mundo)
        double entityRadius = power * 2.0;
        double maxEntityDistSq = entityRadius * entityRadius;
        List<EntityInfo> entityInfos = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            try {
                if (entity.getLocation().distanceSquared(center) <= maxEntityDistSq) {
                    BoundingBox box = entity.getBoundingBox();
                    entityInfos.add(new EntityInfo(
                            entity,
                            entity.getLocation().clone(),
                            box.getMinX(), box.getMinY(), box.getMinZ(),
                            box.getMaxX(), box.getMaxY(), box.getMaxZ()
                    ));
                }
            } catch (Throwable ignored) {}
        }

        int minHeight = Main.getInstance().getNmsHandler().getMinHeight(world);
        int maxHeight = world.getMaxHeight();

        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 1.0f);

        long snapshotTime = System.currentTimeMillis() - start;
        int snapshotCount = snapshots.size();
        int entityCount = entityInfos.size();

        // Hilo Asíncrono: Operaciones matemáticas y raycasting
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            long asyncStart = System.currentTimeMillis();
            Set<BlockPos> blocksToDestroy = ConcurrentHashMap.newKeySet();
            Random random = new Random();
            double cx = center.getX();
            double cy = center.getY();
            double cz = center.getZ();

            // 1. Raycast para bloques
            for (int x = 0; x < 16; ++x) {
                for (int y = 0; y < 16; ++y) {
                    for (int z = 0; z < 16; ++z) {
                        if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) {
                            double dx = (double) x / 15.0D * 2.0D - 1.0D;
                            double dy = (double) y / 15.0D * 2.0D - 1.0D;
                            double dz = (double) z / 15.0D * 2.0D - 1.0D;
                            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            dx /= len;
                            dy /= len;
                            dz /= len;

                            float intensity = power * (0.7F + random.nextFloat() * 0.6F);

                            double rx = cx;
                            double ry = cy;
                            double rz = cz;

                            while (intensity > 0.0F) {
                                int bx = floor(rx);
                                int by = floor(ry);
                                int bz = floor(rz);

                                if (by < minHeight || by >= maxHeight) {
                                    break;
                                }

                                // Skip blocks outside max processing distance
                                double distX = bx - cx;
                                double distY = by - cy;
                                double distZ = bz - cz;
                                if (distX * distX + distY * distY + distZ * distZ <= maxProcDistSq) {
                                    BlockPos pos = new BlockPos(bx, by, bz);
                                    Material mat = getBlockType(pos, snapshots);

                                    if (mat != Material.AIR) {
                                        float resistance = getBlastResistance(mat);
                                        intensity -= (resistance + 0.3F) * 0.3F;
                                    }

                                    if (intensity > 0.0F && breakBlocks) {
                                        blocksToDestroy.add(pos);
                                    }
                                }

                                rx += dx * 0.30000001192092896D;
                                ry += dy * 0.30000001192092896D;
                                rz += dz * 0.30000001192092896D;

                                intensity -= 0.225F;
                            }
                        }
                    }
                }
            }

            // 2. Daño y empuje a entidades
            Map<Entity, EntityDamageInfo> damageMap = new HashMap<>();
            double radius = power * 2.0;

            for (EntityInfo entityInfo : entityInfos) {
                double distance = entityInfo.location.distance(center);
                if (distance <= radius && radius > 0.0) {
                    double fd = 1.0 - (distance / radius);
                    double exposure = calculateExposure(center, entityInfo, snapshots);
                    double damage = ((fd * fd + fd) / 2.0) * 7.0 * radius * exposure + 1.0;

                    Vector dir = entityInfo.location.toVector().subtract(center.toVector());
                    Vector velocity = new Vector(0, 0, 0);
                    if (dir.lengthSquared() > 0.0) {
                        double velocityFactor = fd * exposure;
                        velocity = dir.normalize().multiply(velocityFactor);
                    }

                    damageMap.put(entityInfo.entity, new EntityDamageInfo(damage, velocity));
                }
            }

            long asyncTime = System.currentTimeMillis() - asyncStart;

            // Hilo Principal: Aplicación de daños y actualización de bloques en lotes
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                String initialDebugMsg = "§e[Permadeath-Explosion] Prep síncrona: " + snapshotTime + "ms (" + snapshotCount + " chunks, " + entityCount + " entidades) | Calc asíncrona: " + asyncTime + "ms (" + blocksToDestroy.size() + " bloques rotos)";
                if (debugLog) {
                    Bukkit.getConsoleSender().sendMessage(initialDebugMsg);
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (online.isOp()) {
                            online.sendMessage(initialDebugMsg);
                        }
                    }
                }

                // Daño a entidades
                for (Map.Entry<Entity, EntityDamageInfo> entry : damageMap.entrySet()) {
                    Entity entity = entry.getKey();
                    if (entity.isDead()) continue;

                    EntityDamageInfo info = entry.getValue();
                    EntityDamageEvent event = new EntityDamageEvent(entity, EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, info.damage);
                    Bukkit.getPluginManager().callEvent(event);
                    if (!event.isCancelled()) {
                        if (entity instanceof LivingEntity) {
                            ((LivingEntity) entity).damage(event.getDamage());
                            entity.setLastDamageCause(event);
                        }
                        try {
                            entity.setVelocity(entity.getVelocity().add(info.velocity));
                        } catch (Throwable ignored) {}
                    }
                }

                // Destrucción de bloques
                if (breakBlocks && !blocksToDestroy.isEmpty()) {
                    // Group blocks by chunk coordinates to process them per-chunk
                    Map<Long, List<BlockPos>> blocksByChunk = new HashMap<>();
                    for (BlockPos pos : blocksToDestroy) {
                        long chunkKey = getChunkKey(pos.x >> 4, pos.z >> 4);
                        blocksByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(pos);
                    }

                    List<Long> chunkKeys = new ArrayList<>(blocksByChunk.keySet());
                    final boolean dynamicBatch = Main.getInstance().getConfig().getBoolean("Toggles.Gatos-Supernova.Dynamic-Batch-Size", true);
                    final int initialChunksPerTick = Main.getInstance().getConfig().getInt("Toggles.Gatos-Supernova.Chunks-Por-Tick", 1);

                    new BukkitRunnable() {
                        int chunkIndex = 0;
                        long totalBlocksTime = 0;
                        int currentChunksPerTick = initialChunksPerTick;

                        @Override
                        public void run() {
                            if (chunkIndex >= chunkKeys.size()) {
                                cancel();
                                return;
                            }

                            long batchStart = System.currentTimeMillis();
                            int chunksProcessed = 0;
                            List<Block> changedBlocks = new ArrayList<>();

                            while (chunkIndex < chunkKeys.size() && chunksProcessed < currentChunksPerTick) {
                                long chunkKey = chunkKeys.get(chunkIndex);
                                int cx = (int) (chunkKey >> 32);
                                int cz = (int) chunkKey;

                                List<BlockPos> chunkBlocks = blocksByChunk.get(chunkKey);

                                if (!skipUnloaded || world.isChunkLoaded(cx, cz)) {
                                    for (BlockPos pos : chunkBlocks) {
                                        Block block = world.getBlockAt(pos.x, pos.y, pos.z);

                                        if (block.getType() != Material.AIR && block.getType() != Material.BEDROCK && block.getType() != Material.BARRIER) {
                                            block.setType(Material.AIR, true);
                                            changedBlocks.add(block);

                                            if (placeFire && Math.random() < 0.3333) {
                                                Block below = block.getRelative(BlockFace.DOWN);
                                                if (below.getType().isSolid()) {
                                                    block.setType(Material.FIRE, true);
                                                    changedBlocks.add(block);
                                                }
                                            }
                                        }
                                    }
                                }
                                chunkIndex++;
                                chunksProcessed++;
                            }

                            // Trigger physics and state updates to ensure fluid/foliage updates
                            for (Block block : changedBlocks) {
                                block.getState().update(true, true);
                            }

                            long batchDuration = System.currentTimeMillis() - batchStart;
                            totalBlocksTime += batchDuration;

                            // Dynamically adjust batch size if enabled
                            if (dynamicBatch) {
                                if (batchDuration > 25) { // Slow tick (above 25ms execution time)
                                    currentChunksPerTick = Math.max(1, (int)(currentChunksPerTick * 0.7));
                                } else if (batchDuration < 10) { // Fast tick
                                    currentChunksPerTick = Math.min(100, (int)(currentChunksPerTick * 1.2 + 1));
                                }
                            }

                            if (chunkIndex >= chunkKeys.size()) {
                                String finalDebugMsg = "§e[Permadeath-Explosion] Destrucción completada. Tiempo total de procesamiento de bloques: " + totalBlocksTime + "ms";
                                if (debugLog) {
                                    Bukkit.getConsoleSender().sendMessage(finalDebugMsg);
                                    for (Player online : Bukkit.getOnlinePlayers()) {
                                        if (online.isOp()) {
                                            online.sendMessage(finalDebugMsg);
                                        }
                                    }
                                }
                                cancel();
                            }
                        }
                    }.runTaskTimer(Main.getInstance(), 0L, 1L);
                }
            });
        });
    }

    private static double calculateExposure(Location center, EntityInfo info, Map<Long, ChunkSnapshot> snapshots) {
        double w = info.maxX - info.minX;
        double h = info.maxY - info.minY;
        double d = info.maxZ - info.minZ;

        double stepX = 1.0 / (w * 2.0 + 1.0);
        double stepY = 1.0 / (h * 2.0 + 1.0);
        double stepZ = 1.0 / (d * 2.0 + 1.0);

        if (stepX < 0 || stepY < 0 || stepZ < 0) return 0.0;

        double xOffset = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;

        int total = 0;
        int visible = 0;

        for (double px = 0.0; px <= 1.0; px += stepX) {
            for (double py = 0.0; py <= 1.0; py += stepY) {
                for (double pz = 0.0; pz <= 1.0; pz += stepZ) {
                    double tx = info.minX + w * px + xOffset;
                    double ty = info.minY + h * py;
                    double tz = info.minZ + d * pz + zOffset;

                    if (!isRayBlocked(center.getX(), center.getY(), center.getZ(), tx, ty, tz, snapshots)) {
                        visible++;
                    }
                    total++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) visible / total;
    }

    private static boolean isRayBlocked(double x1, double y1, double z1, double x2, double y2, double z2, Map<Long, ChunkSnapshot> snapshots) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.1) return false;
        dx /= len;
        dy /= len;
        dz /= len;

        double currentDist = 0.0;
        while (currentDist < len) {
            double rx = x1 + dx * currentDist;
            double ry = y1 + dy * currentDist;
            double rz = z1 + dz * currentDist;

            int bx = floor(rx);
            int by = floor(ry);
            int bz = floor(rz);

            BlockPos pos = new BlockPos(bx, by, bz);
            Material mat = getBlockType(pos, snapshots);
            if (mat != Material.AIR && mat.isSolid() && mat != Material.WATER && mat != Material.LAVA) {
                return true;
            }

            currentDist += 0.3;
        }
        return false;
    }

    private static Material getBlockType(BlockPos pos, Map<Long, ChunkSnapshot> snapshots) {
        int cx = pos.x >> 4;
        int cz = pos.z >> 4;
        long key = getChunkKey(cx, cz);
        ChunkSnapshot snapshot = snapshots.get(key);
        if (snapshot == null) {
            return Material.AIR;
        }
        int rx = pos.x & 15;
        int rz = pos.z & 15;
        try {
            return snapshot.getBlockType(rx, pos.y, rz);
        } catch (Exception e) {
            return Material.AIR;
        }
    }

    private static long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int floor(double d) {
        int i = (int) d;
        return d < (double) i ? i - 1 : i;
    }

    private static class BlockPos {
        public final int x, y, z;

        public BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BlockPos)) return false;
            BlockPos blockPos = (BlockPos) o;
            return x == blockPos.x && y == blockPos.y && z == blockPos.z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }
    }

    private static class EntityInfo {
        public final Entity entity;
        public final Location location;
        public final double minX, minY, minZ;
        public final double maxX, maxY, maxZ;

        public EntityInfo(Entity entity, Location location, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.entity = entity;
            this.location = location;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    private static class EntityDamageInfo {
        public final double damage;
        public final Vector velocity;

        public EntityDamageInfo(double damage, Vector velocity) {
            this.damage = damage;
            this.velocity = velocity;
        }
    }
}
