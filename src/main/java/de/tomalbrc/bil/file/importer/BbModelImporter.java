package de.tomalbrc.bil.file.importer;

import com.mojang.math.Axis;
import de.tomalbrc.bil.file.extra.BbResourcePackGenerator;
import de.tomalbrc.bil.file.bbmodel.*;
import de.tomalbrc.bil.core.model.*;
import de.tomalbrc.bil.core.model.Animation;
import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.tracker.DisplayTrackedData;
import gg.moonflower.molangcompiler.api.MolangEnvironment;
import gg.moonflower.molangcompiler.api.MolangRuntime;
import gg.moonflower.molangcompiler.api.exception.MolangRuntimeException;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec2;
import org.apache.commons.lang3.tuple.Triple;
import org.jetbrains.annotations.Nullable;
import org.joml.*;

import java.lang.Math;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BbModelImporter implements ModelImporter<BbModel> {
    private Object2ObjectOpenHashMap<UUID, Node> nodeMap(BbModel model) {
        Object2ObjectOpenHashMap<UUID, Node> nodeMap = new Object2ObjectOpenHashMap<>();
        ObjectArraySet<BbTexture> textures = new ObjectArraySet<>();

        for (BbOutliner.ChildEntry entry: model.outliner) {
            if (entry.isNode()) {
                createBones(model, null, null, model.outliner, nodeMap, textures);
            }
        }

        BbResourcePackGenerator.makeTextures(model, textures);

        return nodeMap;
    }

    void createBones(BbModel model, Node parent, BbOutliner parentOutliner, Collection<BbOutliner.ChildEntry> children, Object2ObjectOpenHashMap<UUID, Node> nodeMap, ObjectArraySet<BbTexture> textures) {
        for (BbOutliner.ChildEntry x: children) {
            if (x.isNode()) {
                BbOutliner outliner = x.outliner;
                PolymerModelData modelData = null;

                if (outliner.hasModel() && outliner.export && !outliner.isHitbox()) {
                    // process model
                    List<BbElement> elements = new ObjectArrayList<>();
                    for (BbElement element: model.elements) {
                        if (outliner.hasUuidChild(element.uuid)) {
                            elements.add(element);
                        }
                    }

                    String modelName = outliner.name;

                    ResourceLocation location = BbResourcePackGenerator.makePart(model, modelName, elements, model.textures);
                    textures.addAll(model.textures);

                    modelData = PolymerResourcePackUtils.requestModel(Items.LEATHER_HORSE_ARMOR, location);
                }

                Vector3f localPos = parentOutliner != null ? outliner.origin.sub(parentOutliner.origin, new Vector3f()) : new Vector3f(outliner.origin);
                Quaternionf localRot = createQuaternion(outliner.rotation);

                var tr = new Node.Transform(localPos.div(16), localRot, outliner.scale);
                if (parent != null)
                    tr.mul(parent.transform());
                else
                    tr.mul(new Matrix4f().rotateY(Mth.PI));

                Node node = new Node(Node.NodeType.bone, parent, tr, outliner.name, outliner.uuid, this.createBoneDisplay(modelData), modelData);
                nodeMap.put(outliner.uuid, node);

                // children
                createBones(model, node, outliner, outliner.children, nodeMap, textures);
            }
        }
    }

    @Nullable
    protected ItemDisplayElement createBoneDisplay(PolymerModelData modelData) {
        if (modelData == null)
            return null;

        ItemDisplayElement element = new ItemDisplayElement();
        element.setModelTransformation(ItemDisplayContext.HEAD);
        element.setInvisible(true);
        element.setInterpolationDuration(2);
        element.getDataTracker().set(DisplayTrackedData.TELEPORTATION_DURATION, 3);

        ItemStack itemStack = new ItemStack(modelData.item());
        itemStack.getOrCreateTag().putInt("CustomModelData", modelData.value());
        if (modelData.item() instanceof DyeableLeatherItem dyeableItem) {
            dyeableItem.setColor(itemStack, -1);
        }

        element.setItem(itemStack);
        return element;
    }

    private Quaternionf createQuaternion(Vector3f eulerAngles) {
        return new Quaternionf()
                .rotateZ(Mth.DEG_TO_RAD * eulerAngles.z)
                .rotateY(Mth.DEG_TO_RAD * eulerAngles.y)
                .rotateX(Mth.DEG_TO_RAD * eulerAngles.x);
    }

    private Reference2ObjectOpenHashMap<UUID, Pose> defaultPose(BbModel model, Object2ObjectOpenHashMap<UUID, Node> nodeMap) {
        Reference2ObjectOpenHashMap<UUID, Pose> res = new Reference2ObjectOpenHashMap<>();

        for (var entry: nodeMap.entrySet()) {
            var bone = entry.getValue();
            if (bone.modelData() != null)
                res.put(bone.uuid(), Pose.of(bone.transform().globalTransform().scale(bone.transform().scale())));
        }

        return res;
    }

    private Reference2ObjectOpenHashMap<UUID, Variant> variants(BbModel model) {
        Reference2ObjectOpenHashMap<UUID, Variant> res = new Reference2ObjectOpenHashMap<>();
        return res;
    }

    private List<Node> nodePath(Node child) {
        List<Node> nodePath = new ObjectArrayList<>();
        while (child != null) {
            nodePath.add(0, child);
            child = child.parent();
        }
        return nodePath;
    }

    private Reference2ObjectOpenHashMap<UUID, Pose> poses(BbModel model, BbAnimation animation, Object2ObjectOpenHashMap<UUID, Node> nodeMap, MolangEnvironment environment, float time) throws MolangRuntimeException {
        Reference2ObjectOpenHashMap<UUID, Pose> poses = new Reference2ObjectOpenHashMap<>();

        for (var entry: nodeMap.entrySet()) {
            if (entry.getValue().modelData() != null) {
                Matrix4f matrix4f = new Matrix4f().rotateY(Mth.PI);
                boolean requiresFrame = false;
                var nodePath = nodePath(entry.getValue());

                for (var node : nodePath) {
                    BbAnimator animator = animation.animators.get(node.uuid());
                    requiresFrame |= animator != null;

                    Vector3fc origin = node.transform().origin();

                    var triple = animator == null ?
                            Triple.of(new Vector3f(), new Vector3f(), new Vector3f(1.f)) :
                            Sampler.sample(animator.keyframes, model.animationVariablePlaceholders, environment, time);

                    Quaternionf localRot = node.transform().rotation().mul(createQuaternion(triple.getMiddle().mul(-1, -1, 1)), new Quaternionf());
                    Vector3f localPos = triple.getLeft().div(16).add(origin);

                    matrix4f.translate(localPos);
                    matrix4f.rotate(localRot);
                    matrix4f.scale(triple.getRight());
                }

                if (requiresFrame)
                    poses.put(entry.getKey(), Pose.of(matrix4f.scale(entry.getValue().transform().scale())));
            }
        }
        return poses;
    }

    static ExecutorService executorService = Executors.newFixedThreadPool(10);

    private Object2ObjectOpenHashMap<String, Animation> animations(BbModel model, Object2ObjectOpenHashMap<UUID, Node> nodeMap) {
        Object2ObjectOpenHashMap<String, Animation> res = new Object2ObjectOpenHashMap<>();
        float step = 0.05f;

        List<CompletableFuture<Void>> futures = new ObjectArrayList<>();
        for (BbAnimation anim: model.animations) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    List<Frame> frames = new ObjectArrayList<>();
                    int frameCount = Math.round(anim.length / step);
                    for (int i = 0; i <= frameCount; i++) {
                        float time = i * step;

                        MolangEnvironment env = MolangRuntime.runtime()
                                .setQuery("life_time", time)
                                .setQuery("anim_time", time)
                                .create();

                        // pose for bone in list of frames for an animation
                        Reference2ObjectOpenHashMap<UUID, Pose> poses = poses(model, anim, nodeMap, env, time);
                        frames.add(new Frame(time, poses, null, null, null, false));
                    }

                    int startDelay = 0;
                    int loopDelay = 0;

                    ReferenceOpenHashSet<UUID> affectedBones = new ReferenceOpenHashSet<>();
                    Frame[] framesArray = frames.toArray(new Frame[frames.size()]);
                    Animation animation = new Animation(framesArray, startDelay, loopDelay, frameCount, anim.loop, affectedBones, false);

                    res.put(anim.name, animation);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, executorService);
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return res;
    }

    private Vec2 size(BbModel model) {
        // TODO: read from element or outliner
        return new Vec2(0.5f,1.f);
    }

    @Override
    public Model importModel(BbModel model) {
        Object2ObjectOpenHashMap<UUID, Node> nodeMap = this.nodeMap(model);
        Reference2ObjectOpenHashMap<UUID, Pose> defaultPose = this.defaultPose(model, nodeMap);
        Object2ObjectOpenHashMap<String, Animation> animations = this.animations(model, nodeMap);
        Reference2ObjectOpenHashMap<UUID, Variant> variants = this.variants(model);

        Model result = new Model(nodeMap, defaultPose, variants, animations, this.size(model));

        return result;
    }
}
