package deliciousbread481.dropdebugforge;  
  
import com.mojang.brigadier.CommandDispatcher;  
import net.minecraft.commands.CommandSourceStack;  
import net.minecraft.commands.Commands;  
import net.minecraft.network.chat.Component;  
import net.minecraft.world.entity.item.ItemEntity;  
import net.minecraft.world.entity.player.Player;  
import net.minecraftforge.common.MinecraftForge;  
import net.minecraftforge.event.RegisterCommandsEvent;  
import net.minecraftforge.event.entity.EntityJoinLevelEvent;  
import net.minecraftforge.event.entity.item.ItemTossEvent;  
import net.minecraftforge.event.entity.living.LivingDropsEvent;  
import net.minecraftforge.event.level.BlockEvent;  
import net.minecraftforge.eventbus.api.Event;  
import net.minecraftforge.eventbus.api.EventPriority;  
import net.minecraftforge.eventbus.api.IEventListener;  
import net.minecraftforge.eventbus.api.SubscribeEvent;  
import net.minecraftforge.fml.common.Mod;  
import org.apache.logging.log4j.LogManager;  
import org.apache.logging.log4j.Logger;  
  
import java.lang.reflect.Field;  
import java.util.*;  
import java.util.concurrent.ConcurrentHashMap;  
  
@Mod("dropdebugforge")  
public class DropDebugForge {  
  
    private static final Logger LOG = LogManager.getLogger("DropDebugForge");  
    private static boolean debugEnabled = false;  
  
    // ===== BlockEvent.BreakEvent 追踪 =====  
    private final Map<String, Boolean> blockBreakWasCancelled = new ConcurrentHashMap<>();  
    private final Map<String, EventPriority> blockBreakCancelledAt = new ConcurrentHashMap<>();  
  
    // ===== LivingDropsEvent 追踪 =====  
    private final Map<UUID, Integer> livingDropsCount = new ConcurrentHashMap<>();  
    private final Map<UUID, EventPriority> livingDropsClearedAt = new ConcurrentHashMap<>();  
  
    // ===== ItemTossEvent 追踪 =====  
    private final Map<String, EventPriority> itemTossCancelledAt = new ConcurrentHashMap<>();  
    // ===== EntityJoinLevelEvent 追踪 =====  
    private final Map<String, EventPriority> entityJoinCancelledAt = new ConcurrentHashMap<>();
  
    public DropDebugForge() {  
        MinecraftForge.EVENT_BUS.register(this);  
        LOG.info("[DropDebugForge] Mod loaded. Use /dropdebugforge to toggle.");  
    }  
  
    // ==================== 命令注册 ====================  
  
    @SubscribeEvent  
    public void onRegisterCommands(RegisterCommandsEvent event) {  
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();  
        dispatcher.register(  
            Commands.literal("dropdebugforge")  
                .requires(src -> src.hasPermission(2))  
                .executes(ctx -> {  
                    debugEnabled = !debugEnabled;  
                    String status = debugEnabled ? "ON" : "OFF";  
                    ctx.getSource().sendSuccess(  
                        () -> Component.literal("[DropDebugForge] Debug mode: " + status), true);  
                    LOG.info("[DropDebugForge] Debug mode: {}", status);  
                    return 1;  
                })  
        );  
    }  
  
    // ==================== 工具方法 ====================  
  
    private String blockKey(BlockEvent.BreakEvent event) {  
        return event.getPlayer().getName().getString() + ":"  
                + event.getPos().getX() + ":"  
                + event.getPos().getY() + ":"  
                + event.getPos().getZ();  
    }  
  
    /**  
     * 尝试获取 Forge 事件总线上注册的所有监听器信息。  
     * 通过反射获取 EventBus 内部的 listeners，解析出类名来推断 mod 来源。  
     */  
    private String getForgeListenersInfo(Event event) {  
        StringBuilder sb = new StringBuilder();  
        try {  
            // Forge EventBus 内部使用 ListenerList  
            // event.getListenerList() 返回 ListenerList  
            var listenerList = event.getListenerList();  
            
            IEventListener[] listeners = listenerList.getListeners(0);
            Set<String> seen = new LinkedHashSet<>();  
            for (IEventListener listener : listeners) {  
                String listenerStr = listener.toString();  
                // 尝试获取更有用的类名信息  
                String className = listener.getClass().getName();  
  
                // ASMEventHandler 包含实际的处理方法信息  
                String info = extractListenerInfo(listener);  
                if (info != null && !seen.contains(info)) {  
                    seen.add(info);  
                    sb.append("\n    - ").append(info);  
                }  
            }  
        } catch (Exception e) {  
            sb.append("\n    [Failed to retrieve listeners: ").append(e.getMessage()).append("]");  
        }  
        return sb.toString();  
    }  
  
    /**  
     * 从 IEventListener 中提取有用的信息（mod 类名、方法名等）  
     */  
    private String extractListenerInfo(IEventListener listener) {  
        try {  
            // Forge 的 ASMEventHandler 内部有一个 handler 字段指向实际的监听对象  
            Class<?> clazz = listener.getClass();  
            String className = clazz.getName();  
  
            // 如果是 ASMEventHandler，尝试反射获取内部信息  
            if (className.contains("ASMEventHandler")) {  
                try {  
                    Field subInfoField = clazz.getDeclaredField("subInfo");  
                    subInfoField.setAccessible(true);  
                    Object subInfo = subInfoField.get(listener);  
                    if (subInfo != null) {  
                        return subInfo.toString();  
                    }  
                } catch (NoSuchFieldException e) {  
                    // 尝试其他字段  
                    try {  
                        Field handlerField = clazz.getDeclaredField("handler");  
                        handlerField.setAccessible(true);  
                        Object handler = handlerField.get(listener);  
                        if (handler != null) {  
                            return "Handler: " + handler.getClass().getName();  
                        }  
                    } catch (NoSuchFieldException e2) {  
                        // ignore  
                    }  
                }  
            }  
  
            // 如果是 lambda 或其他类型，直接返回类名  
            if (!className.contains("DropDebugForge")) {  
                return "Listener: " + className + " (" + listener + ")";  
            }  
        } catch (Exception e) {  
            return "Listener: " + listener.getClass().getName() + " [error: " + e.getMessage() + "]";  
        }  
        return null;  
    }  
    
    // ==================== BlockEvent.BreakEvent 多优先级追踪 ====================  
  
    @SubscribeEvent(priority = EventPriority.HIGHEST) // Forge: HIGHEST = 最先执行  
    public void onBlockBreakHighest(BlockEvent.BreakEvent event) {  
        if (!debugEnabled) return;  
        String key = blockKey(event);  
        blockBreakCancelledAt.remove(key);  
        blockBreakWasCancelled.remove(key);  
        if (event.isCanceled()) {  
            blockBreakCancelledAt.put(key, EventPriority.HIGHEST);  
            blockBreakWasCancelled.put(key, true);  
        }  
    }  
  
    @SubscribeEvent(priority = EventPriority.HIGH)  
    public void onBlockBreakHigh(BlockEvent.BreakEvent event) {  
        if (!debugEnabled) return;  
        String key = blockKey(event);  
        if (event.isCanceled() && !blockBreakCancelledAt.containsKey(key)) {  
            blockBreakCancelledAt.put(key, EventPriority.HIGH);  
        }  
    }  
  
    @SubscribeEvent(priority = EventPriority.NORMAL)  
    public void onBlockBreakNormal(BlockEvent.BreakEvent event) {  
        if (!debugEnabled) return;  
        String key = blockKey(event);  
        if (event.isCanceled() && !blockBreakCancelledAt.containsKey(key)) {  
            blockBreakCancelledAt.put(key, EventPriority.NORMAL);  
        }  
    }  
  
    @SubscribeEvent(priority = EventPriority.LOW)  
    public void onBlockBreakLow(BlockEvent.BreakEvent event) {  
        if (!debugEnabled) return;  
        String key = blockKey(event);  
        if (event.isCanceled() && !blockBreakCancelledAt.containsKey(key)) {  
            blockBreakCancelledAt.put(key, EventPriority.LOW);  
        }  
    }
    
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true) // LOWEST = 最后执行（相当于 Bukkit 的 MONITOR）    
    public void onBlockBreakLowest(BlockEvent.BreakEvent event) {    
        if (!debugEnabled) return;    
        String key = blockKey(event);    
  
        if (event.isCanceled()) {    
            if (!blockBreakCancelledAt.containsKey(key)) {    
                blockBreakCancelledAt.put(key, EventPriority.LOWEST);    
            }    
            EventPriority cancelPriority = blockBreakCancelledAt.get(key);    
  
            LOG.warn("[DropDebugForge] [BlockBreakEvent] CANCELLED! Block: {} at {} by {}",    
                    event.getState().getBlock().getName().getString(),    
                    event.getPos(),    
                    event.getPlayer().getName().getString());    
            LOG.warn("[DropDebugForge] [BlockBreakEvent] >>> First cancelled at Forge priority: {}", cancelPriority);    
            LOG.warn("[DropDebugForge] [BlockBreakEvent] >>> Registered Forge listeners:{}", getForgeListenersInfo(event));    
            LOG.warn("[DropDebugForge] [BlockBreakEvent] >>> Stack trace:");    
            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {    
                LOG.warn("    at {}", ste);    
            }    
        } else {    
            LOG.info("[DropDebugForge] [BlockBreakEvent] OK. Block: {} at {} by {}",    
                    event.getState().getBlock().getName().getString(),    
                    event.getPos(),    
                    event.getPlayer().getName().getString());    
        }    
  
        blockBreakCancelledAt.remove(key);    
        blockBreakWasCancelled.remove(key);    
    }
  
    // ==================== LivingDropsEvent 多优先级追踪 ====================  
  
    @SubscribeEvent(priority = EventPriority.HIGHEST)  
    public void onLivingDropsHighest(LivingDropsEvent event) {  
        if (!debugEnabled) return;  
        UUID id = event.getEntity().getUUID();  
        livingDropsClearedAt.remove(id);  
        livingDropsCount.put(id, event.getDrops().size());  
        if (event.isCanceled()) {  
            livingDropsClearedAt.put(id, EventPriority.HIGHEST);  
        }  
    }  
  
    @SubscribeEvent(priority = EventPriority.HIGH)  
    public void onLivingDropsHigh(LivingDropsEvent event) {  
        if (!debugEnabled) return;  
        UUID id = event.getEntity().getUUID();  
        int prev = livingDropsCount.getOrDefault(id, -1);  
        int now = event.getDrops().size();  
        livingDropsCount.put(id, now);  
        if ((event.isCanceled() || (now == 0 && prev > 0)) && !livingDropsClearedAt.containsKey(id)) {  
            livingDropsClearedAt.put(id, EventPriority.HIGH);  
        }  
    }  
  
    @SubscribeEvent(priority = EventPriority.NORMAL)  
    public void onLivingDropsNormal(LivingDropsEvent event) {  
        if (!debugEnabled) return;  
        UUID id = event.getEntity().getUUID();  
        int prev = livingDropsCount.getOrDefault(id, -1);  
        int now = event.getDrops().size();  
        livingDropsCount.put(id, now);  
        if ((event.isCanceled() || (now == 0 && prev > 0)) && !livingDropsClearedAt.containsKey(id)) {  
            livingDropsClearedAt.put(id, EventPriority.NORMAL);  
        }  
    }
    
    @SubscribeEvent(priority = EventPriority.LOW)  
    public void onLivingDropsLow(LivingDropsEvent event) {  
        if (!debugEnabled) return;  
        UUID id = event.getEntity().getUUID();  
        int prev = livingDropsCount.getOrDefault(id, -1);  
        int now = event.getDrops().size();  
        livingDropsCount.put(id, now);  
        if ((event.isCanceled() || (now == 0 && prev > 0)) && !livingDropsClearedAt.containsKey(id)) {  
            livingDropsClearedAt.put(id, EventPriority.LOW);  
        }  
    }  
  
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)    
    public void onLivingDropsLowest(LivingDropsEvent event) {    
        if (!debugEnabled) return;    
        UUID id = event.getEntity().getUUID();    
  
        if (event.isCanceled()) {    
            EventPriority cancelPriority = livingDropsClearedAt.getOrDefault(id, EventPriority.LOWEST);    
            LOG.warn("[DropDebugForge] [LivingDropsEvent] CANCELLED! Entity: {} at {}",    
                    event.getEntity().getType().toShortString(),    
                    event.getEntity().position());    
            LOG.warn("[DropDebugForge] [LivingDropsEvent] >>> Event cancelled at Forge priority: {}", cancelPriority);    
            LOG.warn("[DropDebugForge] [LivingDropsEvent] >>> Registered Forge listeners:{}", getForgeListenersInfo(event));    
            LOG.warn("[DropDebugForge] [LivingDropsEvent] >>> Stack trace:");    
            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {    
                LOG.warn("    at {}", ste);    
            }    
        } else if (event.getDrops().isEmpty()) {    
            EventPriority clearPriority = livingDropsClearedAt.get(id);    
            LOG.warn("[DropDebugForge] [LivingDropsEvent] Drops EMPTY! Entity: {} at {}",    
                    event.getEntity().getType().toShortString(),    
                    event.getEntity().position());    
            if (clearPriority != null) {    
                LOG.warn("[DropDebugForge] [LivingDropsEvent] >>> Drops cleared at Forge priority: {}", clearPriority);    
            } else {    
                LOG.warn("[DropDebugForge] [LivingDropsEvent] >>> Drops were already empty at HIGHEST (no natural drops or cleared before listeners)");    
            }    
            LOG.warn("[DropDebugForge] [LivingDropsEvent] >>> Registered Forge listeners:{}", getForgeListenersInfo(event));    
            LOG.warn("[DropDebugForge] [LivingDropsEvent] >>> Stack trace:");    
            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {    
                LOG.warn("    at {}", ste);    
            }    
        } else {    
            LOG.info("[DropDebugForge] [LivingDropsEvent] OK. Entity: {} Drops: {} Items: {}",    
                    event.getEntity().getType().toShortString(),    
                    event.getDrops().size(),    
                    event.getDrops().stream()    
                            .map(e -> e.getItem().getItem().toString())    
                            .toList());    
        }    
  
        livingDropsCount.remove(id);    
        livingDropsClearedAt.remove(id);    
    }
    
    // ==================== ItemTossEvent（玩家丢弃物品） ====================  
  
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)    
    public void onItemToss(ItemTossEvent event) {    
        if (!debugEnabled) return;    
        if (event.isCanceled()) {    
            LOG.warn("[DropDebugForge] [ItemTossEvent] CANCELLED! Player: {} Item: {}",    
                    event.getPlayer().getName().getString(),    
                    event.getEntity().getItem().getItem());    
            LOG.warn("[DropDebugForge] [ItemTossEvent] >>> Registered Forge listeners:{}", getForgeListenersInfo(event));    
            LOG.warn("[DropDebugForge] [ItemTossEvent] >>> Stack trace:");    
            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {    
                LOG.warn("    at {}", ste);    
            }    
        }    
    }
  
    // ==================== EntityJoinLevelEvent（物品实体生成） ====================  
  
    // ==================== EntityJoinLevelEvent 多优先级追踪 ====================  
  
    private String entityJoinKey(ItemEntity itemEntity) {    
        return itemEntity.getItem().getItem().toString() + ":"    
                + (int) itemEntity.getX() + ":"    
                + (int) itemEntity.getY() + ":"    
                + (int) itemEntity.getZ();    
    }    
  
    @SubscribeEvent(priority = EventPriority.HIGHEST)    
    public void onEntityJoinLevelHighest(EntityJoinLevelEvent event) {    
        if (!debugEnabled) return;    
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;    
        String key = entityJoinKey(itemEntity);    
        entityJoinCancelledAt.remove(key);    
        if (event.isCanceled()) {    
            entityJoinCancelledAt.put(key, EventPriority.HIGHEST);    
        }    
    }    
  
    @SubscribeEvent(priority = EventPriority.HIGH)    
    public void onEntityJoinLevelHigh(EntityJoinLevelEvent event) {    
        if (!debugEnabled) return;    
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;    
        String key = entityJoinKey(itemEntity);    
        if (event.isCanceled() && !entityJoinCancelledAt.containsKey(key)) {    
            entityJoinCancelledAt.put(key, EventPriority.HIGH);    
        }    
    }    
  
    @SubscribeEvent(priority = EventPriority.NORMAL)    
    public void onEntityJoinLevelNormal(EntityJoinLevelEvent event) {    
        if (!debugEnabled) return;    
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;    
        String key = entityJoinKey(itemEntity);    
        if (event.isCanceled() && !entityJoinCancelledAt.containsKey(key)) {    
            entityJoinCancelledAt.put(key, EventPriority.NORMAL);    
        }    
    }    
  
    @SubscribeEvent(priority = EventPriority.LOW)    
    public void onEntityJoinLevelLow(EntityJoinLevelEvent event) {    
        if (!debugEnabled) return;    
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;    
        String key = entityJoinKey(itemEntity);    
        if (event.isCanceled() && !entityJoinCancelledAt.containsKey(key)) {    
            entityJoinCancelledAt.put(key, EventPriority.LOW);    
        }    
    }    
  
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)    
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {    
        if (!debugEnabled) return;    
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;    
  
        if (event.isCanceled()) {    
            String key = entityJoinKey(itemEntity);    
            if (!entityJoinCancelledAt.containsKey(key)) {    
                entityJoinCancelledAt.put(key, EventPriority.LOWEST);    
            }    
            EventPriority cancelPriority = entityJoinCancelledAt.get(key);    
  
            LOG.warn("[DropDebugForge] [EntityJoinLevelEvent] CANCELLED for ItemEntity! Item: {} at {}",    
                    itemEntity.getItem().getItem(),    
                    itemEntity.position());    
            LOG.warn("[DropDebugForge] [EntityJoinLevelEvent] >>> ItemStack details: item={}, count={}, isEmpty={}",    
                    itemEntity.getItem().getItem(),    
                    itemEntity.getItem().getCount(),    
                    itemEntity.getItem().isEmpty());    
            LOG.warn("[DropDebugForge] [EntityJoinLevelEvent] >>> First cancelled at Forge priority: {}", cancelPriority);    
            LOG.warn("[DropDebugForge] [EntityJoinLevelEvent] >>> Registered Forge listeners:{}", getForgeListenersInfo(event));    
            LOG.warn("[DropDebugForge] [EntityJoinLevelEvent] >>> Stack trace:");    
            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {    
                LOG.warn("    at {}", ste);    
            }    
  
            entityJoinCancelledAt.remove(key);    
        }    
    }    
}