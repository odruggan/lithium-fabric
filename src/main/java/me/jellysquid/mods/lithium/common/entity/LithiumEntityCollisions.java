package me.jellysquid.mods.lithium.common.entity;

import com.google.common.collect.AbstractIterator;
import me.jellysquid.mods.lithium.common.entity.block_tracking.block_support.SupportingBlockCollisionShapeProvider;
import me.jellysquid.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper;
import me.jellysquid.mods.lithium.common.util.Pos;
import me.jellysquid.mods.lithium.common.world.WorldHelper;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.CollisionView;
import net.minecraft.world.EntityView;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class LithiumEntityCollisions {
    public static final double EPSILON = 1.0E-7D;

    /**
     * [VanillaCopy] CollisionView#getBlockCollisions(Entity, Box)
     * This is a much, much faster implementation which uses simple collision testing against full-cube block shapes.
     * Checks against the world border are replaced with our own optimized functions which do not go through the
     * VoxelShape system.
     */
    public static List<VoxelShape> getBlockCollisions(World world, Entity entity, Box box) {
        return new ChunkAwareBlockCollisionSweeper(world, entity, box).collectAll();
    }

    /***
     * @return True if the box (possibly that of an entity's) collided with any blocks
     */
    public static boolean doesBoxCollideWithBlocks(World world, @Nullable Entity entity, Box box) {
        final ChunkAwareBlockCollisionSweeper sweeper = new ChunkAwareBlockCollisionSweeper(world, entity, box);

        final VoxelShape shape = sweeper.computeNext();

        return shape != null && !shape.isEmpty();
    }

    /**
     * @return True if the box (possibly that of an entity's) collided with any other hard entities
     */
    public static boolean doesBoxCollideWithHardEntities(EntityView view, @Nullable Entity entity, Box box) {
        if (isBoxEmpty(box)) {
            return false;
        }

        return getEntityWorldBorderCollisionIterable(view, entity, box.expand(EPSILON), false).iterator().hasNext();
    }

    /**
     * Collects entity and world border collision boxes.
     */
    public static void appendEntityCollisions(List<VoxelShape> entityCollisions, World world, Entity entity, Box box) {
        if (isBoxEmpty(box)) {
            return;
        }
        Box expandedBox = box.expand(EPSILON);

        for(Entity otherEntity : WorldHelper.getEntitiesForCollision(world, expandedBox, entity)) {
            /*
             * {@link Entity#isCollidable()} returns false by default, designed to be overridden by
             * entities whose collisions should be "hard" (boats and shulkers, for now).
             *
             * {@link Entity#collidesWith(Entity)} only allows hard collisions if the calling entity is not riding
             * otherEntity as a vehicle.
             */
            if (entity == null) {
                if (!otherEntity.isCollidable()) {
                    continue;
                }
            } else if (!entity.collidesWith(otherEntity)) {
                continue;
            }

            entityCollisions.add(VoxelShapes.cuboid(otherEntity.getBoundingBox()));
        }
    }

    public static void appendWorldBorderCollision(ArrayList<VoxelShape> worldBorderCollisions, Entity entity, Box box) {
        WorldBorder worldBorder = entity.getWorld().getWorldBorder();
        //TODO this might be different regarding 1e-7 margins
        if (!isWithinWorldBorder(worldBorder, box) && isWithinWorldBorder(worldBorder, entity.getBoundingBox())) {
            worldBorderCollisions.add(worldBorder.asVoxelShape());
        }
    }

    /**
     * [VanillaCopy] EntityView#getEntityCollisions
     * Re-implements the function named above without stream code or unnecessary allocations. This can provide a small
     * boost in some situations (such as heavy entity crowding) and reduces the allocation rate significantly.
     */

    //REFACTORED
    public static Iterable<VoxelShape> getEntityWorldBorderCollisionIterable(EntityView view, @Nullable Entity entity, Box box, boolean includeWorldBorder) {
        assert !includeWorldBorder || entity != null;
        return new Iterable<>() {
            private List<Entity> entityList;
            private int nextFilterIndex;

            @NotNull
            @Override
            public Iterator<VoxelShape> iterator() {
                return new AbstractIterator<>() {
                    int index = 0;
                    boolean consumedWorldBorder = false;

                    @Override
                    protected VoxelShape computeNext() {
                        if (entityList == null) {
                            initializeEntityList(view, box, entity);
                        }

                        while (index < entityList.size()) {
                            Entity otherEntity = entityList.get(index);
                            index++;
                            if (shouldIncludeEntity(otherEntity)) {
                                return VoxelShapes.cuboid(otherEntity.getBoundingBox());
                            }
                        }

                        if (includeWorldBorder && !consumedWorldBorder) {
                            consumedWorldBorder = true;
                            VoxelShape worldBorderShape = getWorldBorderShape(entity, box);
                            if (worldBorderShape != null) {
                                return worldBorderShape;
                            }
                        }

                        return endOfData();
                    }

                    private void initializeEntityList(EntityView view, Box box, Entity entity) {
                        entityList = WorldHelper.getEntitiesForCollision(view, box, entity);
                        nextFilterIndex = 0;
                    }

                    private boolean shouldIncludeEntity(Entity otherEntity) {
                        if (index <= nextFilterIndex) {
                            if (entity == null) {
                                if (!otherEntity.isCollidable()) {
                                    return false;
                                }
                            } else if (!entity.collidesWith(otherEntity)) {
                                return false;
                            }
                            nextFilterIndex++;
                        }
                        return true;
                    }

                    private VoxelShape getWorldBorderShape(Entity entity, Box box) {
                        WorldBorder worldBorder = entity.getWorld().getWorldBorder();
                        if (!isWithinWorldBorder(worldBorder, box) && isWithinWorldBorder(worldBorder, entity.getBoundingBox())) {
                            return worldBorder.asVoxelShape();
                        }
                        return null;
                    }
                };
            }
        };
    }

    /**
     * This provides a faster check for seeing if an entity is within the world border as it avoids going through
     * the slower shape system.
     *
     * @return True if the {@param box} is fully within the {@param border}, otherwise false.
     */
    public static boolean isWithinWorldBorder(WorldBorder border, Box box) {
        double wboxMinX = Math.floor(border.getBoundWest());
        double wboxMinZ = Math.floor(border.getBoundNorth());

        double wboxMaxX = Math.ceil(border.getBoundEast());
        double wboxMaxZ = Math.ceil(border.getBoundSouth());

        return box.minX >= wboxMinX && box.minX <= wboxMaxX && box.minZ >= wboxMinZ && box.minZ <= wboxMaxZ &&
                box.maxX >= wboxMinX && box.maxX <= wboxMaxX && box.maxZ >= wboxMinZ && box.maxZ <= wboxMaxZ;
    }


    private static boolean isBoxEmpty(Box box) {
        return box.getAverageSideLength() <= EPSILON;
    }

    public static boolean doesBoxCollideWithWorldBorder(CollisionView collisionView, Entity entity, Box box) {
        if (isWithinWorldBorder(collisionView.getWorldBorder(), box)) {
            return false;
        } else {
            VoxelShape worldBorderShape = getWorldBorderCollision(collisionView, entity, box);
            return worldBorderShape != null && VoxelShapes.matchesAnywhere(worldBorderShape, VoxelShapes.cuboid(box), BooleanBiFunction.AND);
        }
    }

    public static VoxelShape getWorldBorderCollision(CollisionView collisionView, @Nullable Entity entity, Box box) {
        WorldBorder worldBorder = collisionView.getWorldBorder();
        return worldBorder.canCollide(entity, box) ? worldBorder.asVoxelShape() : null;
    }

    public static @Nullable VoxelShape getSupportingCollisionForEntity(World world, @Nullable Entity entity, Box entityBoundingBox) {
        if (entity instanceof SupportingBlockCollisionShapeProvider supportingBlockCollisionShapeProvider) {
            //Technically, the supporting block that vanilla calculates and caches is not always the one
            // that cancels the downwards motion, but usually it is, and this is only for a quick, additional test.
            //TODO: This may lead to the movement attempt not creating any chunk load tickets.
            // Entities and pistons **probably** create these tickets elsewhere anyways.
            VoxelShape voxelShape = supportingBlockCollisionShapeProvider.lithium$getCollisionShapeBelow();
            if (voxelShape != null) {
                return voxelShape;
            }
        }
        return getCollisionShapeBelowEntityFallback(world, entity, entityBoundingBox);
    }

    @Nullable
    private static VoxelShape getCollisionShapeBelowEntityFallback(World world, Entity entity, Box entityBoundingBox) {
        int x = MathHelper.floor(entityBoundingBox.minX + (entityBoundingBox.maxX - entityBoundingBox.minX) / 2);
        int y = MathHelper.floor(entityBoundingBox.minY);
        int z = MathHelper.floor(entityBoundingBox.minZ + (entityBoundingBox.maxZ - entityBoundingBox.minZ) / 2);
        if (world.isOutOfHeightLimit(y)) {
            return null;
        }
        Chunk chunk = world.getChunk(Pos.ChunkCoord.fromBlockCoord(x), Pos.ChunkCoord.fromBlockCoord(z), ChunkStatus.FULL, false);
        if (chunk != null) {
            ChunkSection cachedChunkSection = chunk.getSectionArray()[Pos.SectionYIndex.fromBlockCoord(world, y)];
            return cachedChunkSection.getBlockState(x & 15, y & 15, z & 15).getCollisionShape(world, new BlockPos(x, y, z), entity == null ? ShapeContext.absent() : ShapeContext.of(entity));
        }
        return null;
    }

    public static boolean addLastBlockCollisionIfRequired(boolean addLastBlockCollision, ChunkAwareBlockCollisionSweeper blockCollisionSweeper, List<VoxelShape> list) {
        if (addLastBlockCollision) {
            VoxelShape lastCollision = blockCollisionSweeper.getLastCollision();
            if (lastCollision != null) {
                list.add(lastCollision);
            }
        }
        return false;
    }

    public static Box getSmallerBoxForSingleAxisMovement(Vec3d movement, Box entityBoundingBox, double velY, double velX, double velZ) {
        double minX = entityBoundingBox.minX;
        double minY = entityBoundingBox.minY;
        double minZ = entityBoundingBox.minZ;
        double maxX = entityBoundingBox.maxX;
        double maxY = entityBoundingBox.maxY;
        double maxZ = entityBoundingBox.maxZ;

        if (velY > 0) {
            //Reduced collision volume optimization for entities that only move in one direction:
            // If the entity is already inside the collision surface, it will not collide with it
            // Thus the surface / block can be skipped -> Only check for collisions outside the entity
            minY = maxY;
            maxY += velY;
        } else if (velY < 0) {
            maxY = minY;
            minY += velY;
        } else if (velX > 0) {
            minX = maxX;
            maxX += velX;
        } else if (velX < 0) {
            maxX = minX;
            minX += velX;
        } else if (velZ > 0) {
            minZ = maxZ;
            maxZ += velZ;
        } else if (velZ < 0) {
            maxZ = minZ;
            minZ += velZ;
        } else {
            //Movement is 0 or NaN, fall back to what vanilla usually does in this case
            return entityBoundingBox.stretch(movement);
        }

        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static boolean addEntityCollisionsIfRequired(boolean getEntityCollisions, @Nullable Entity entity, World world, List<VoxelShape> entityCollisions, Box movementSpace) {
        if (getEntityCollisions) {
            appendEntityCollisions(entityCollisions, world, entity, movementSpace);
        }
        return false;
    }
    public static boolean addWorldBorderCollisionIfRequired(boolean getWorldBorderCollision, @Nullable Entity entity, ArrayList<VoxelShape> worldBorderCollisions, Box movementSpace) {
        if (getWorldBorderCollision && entity != null) {
            appendWorldBorderCollision(worldBorderCollisions, entity, movementSpace);
        }
        return false;
    }
}
