package com.obsidianevo.item.custom;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EvolvingSwordItem extends SwordItem {
    public static final String KILLS_KEY = "KillsCount";
    public static final String DAMAGE_DEALT_KEY = "DamageDealt";

    public EvolvingSwordItem(Tier pTier, int pAttackDamageModifier, float pAttackSpeedModifier, Properties pProperties) {
        super(pTier, pAttackDamageModifier, pAttackSpeedModifier, pProperties.durability(100000));
    }

    public static int getKills(ItemStack stack) {
        if (!stack.hasTag()) return 0;
        return stack.getTag().getInt(KILLS_KEY);
    }

    public static void addKill(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt(KILLS_KEY, tag.getInt(KILLS_KEY) + 1);
    }

    public static float getDamageDealt(ItemStack stack) {
        if (!stack.hasTag()) return 0.0f;
        return stack.getTag().getFloat(DAMAGE_DEALT_KEY);
    }

    public static void addDamageDealt(ItemStack stack, float amount) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putFloat(DAMAGE_DEALT_KEY, tag.getFloat(DAMAGE_DEALT_KEY) + amount);
    }

    public static int getSwordLevel(ItemStack stack) {
        int kills = getKills(stack);
        if (kills >= 201) return 6;
        if (kills >= 101) return 5;
        if (kills >= 81) return 4;
        if (kills >= 51) return 3;
        if (kills >= 16) return 2;
        return 1;
    }

    public static float getBonusDamage(ItemStack stack) {
        return (getKills(stack) * 0.5f) + (getDamageDealt(stack) * 0.02f);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (!level.isClientSide() && getSwordLevel(stack) >= 6) {
            if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FIRE_ASPECT, stack) < Enchantments.FIRE_ASPECT.getMaxLevel()) {
                stack.enchant(Enchantments.FIRE_ASPECT, Enchantments.FIRE_ASPECT.getMaxLevel());
            }
        }
    }

    @Override
    public boolean hurtEnemy(ItemStack pStack, LivingEntity pTarget, LivingEntity pAttacker) {
        if (!pAttacker.level.isClientSide() && pAttacker instanceof Player player) {
            int swordLevel = getSwordLevel(pStack);
            float bonus = getBonusDamage(pStack);
            float totalHitDamage = 8.0f + bonus;

            // Aplicar daño evolutivo que ignora invulnerabilidad de ticks y armadura pesada
            if (bonus > 0) {
                pTarget.invulnerableTime = 0;
                pTarget.hurt(DamageSource.playerAttack(player).bypassArmor(), bonus);
            }

            // NIVEL 1+: Robo de vida (cura el 20% del daño infligido, mínimo 1 corazón)
            float lifeSteal = Math.max(2.0f, totalHitDamage * 0.20f);
            player.heal(lifeSteal);

            // NIVEL 3+: Efecto de fuego (Ampliado en Nivel 6)
            if (swordLevel >= 6) {
                pTarget.setSecondsOnFire(15);
                pTarget.invulnerableTime = 0;
                pTarget.hurt(DamageSource.IN_FIRE, 15.0f + (getKills(pStack) * 0.1f));
                if (player.level instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(ParticleTypes.LAVA, pTarget.getX(), pTarget.getY() + 1.0, pTarget.getZ(), 12, 0.4, 0.4, 0.4, 0.1);
                }
            } else if (swordLevel >= 3) {
                pTarget.setSecondsOnFire(5);
            }

            // NIVEL 2+: Daño en área (AoE) aumentado y mayor rango (4.5 bloques)
            if (swordLevel >= 2) {
                double aoeRange = 4.5D;
                AABB aoeBox = pTarget.getBoundingBox().inflate(aoeRange, 2.0D, aoeRange);
                List<LivingEntity> nearbyEntities = player.level.getEntitiesOfClass(LivingEntity.class, aoeBox,
                        e -> e != player && e != pTarget && e.isAlive());

                float aoeDamage = totalHitDamage * 0.65f;
                for (LivingEntity nearby : nearbyEntities) {
                    nearby.invulnerableTime = 0;
                    nearby.hurt(DamageSource.playerAttack(player).bypassArmor(), aoeDamage);
                    if (swordLevel >= 6) {
                        nearby.setSecondsOnFire(15);
                    } else if (swordLevel >= 3) {
                        nearby.setSecondsOnFire(5);
                    }
                    if (nearby.isDeadOrDying()) {
                        addKill(pStack);
                    }
                }

                if (player.level instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(ParticleTypes.SWEEP_ATTACK, pTarget.getX(), pTarget.getY() + 1.0, pTarget.getZ(), 5, 1.2, 0.3, 1.2, 0.0);
                }
            }

            addDamageDealt(pStack, totalHitDamage);
            if (pTarget.isDeadOrDying()) {
                addKill(pStack);
            }
        }
        return super.hurtEnemy(pStack, pTarget, pAttacker);
    }

    // NIVEL 4 y 5+: Habilidad de Onda de Aire (+ Rayos en Nivel 5) con Clic Derecho
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        int swordLevel = getSwordLevel(stack);

        if (swordLevel >= 4) {
            if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
                Vec3 look = player.getLookAngle();
                Vec3 startPos = player.getEyePosition();

                // Daño de la onda usando el multiplicador de almas
                float waveDamage = 12.0f + (getKills(stack) * 0.75f);
                Set<LivingEntity> hitEntities = new HashSet<>();

                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 1.2f, 1.5f);

                // Trazar la onda de aire hasta 20 bloques hacia adelante
                for (double d = 1.5; d <= 20.0; d += 1.0) {
                    Vec3 point = startPos.add(look.scale(d));
                    serverLevel.sendParticles(ParticleTypes.CLOUD, point.x, point.y, point.z, 6, 0.35, 0.35, 0.35, 0.02);
                    serverLevel.sendParticles(ParticleTypes.SWEEP_ATTACK, point.x, point.y, point.z, 1, 0.1, 0.1, 0.1, 0.0);

                    AABB hitBox = new AABB(point.x - 1.75, point.y - 1.75, point.z - 1.75,
                                           point.x + 1.75, point.y + 1.75, point.z + 1.75);
                    List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, hitBox,
                            e -> e != player && e.isAlive());

                    for (LivingEntity target : targets) {
                        if (hitEntities.add(target)) {
                            target.invulnerableTime = 0;
                            target.hurt(DamageSource.playerAttack(player).bypassArmor(), waveDamage);

                            // Empuje de la onda de aire
                            target.push(look.x * 1.4, 0.45, look.z * 1.4);

                            // Fuego si es Nivel 6
                            if (swordLevel >= 6) {
                                target.setSecondsOnFire(15);
                            }

                            // NIVEL 5+: Invocar Rayos junto con la onda de aire
                            if (swordLevel >= 5) {
                                LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(serverLevel);
                                if (lightning != null) {
                                    lightning.moveTo(target.getX(), target.getY(), target.getZ());
                                    serverLevel.addFreshEntity(lightning);
                                }
                                target.invulnerableTime = 0;
                                target.hurt(DamageSource.LIGHTNING_bolt, 10.0f + (getKills(stack) * 0.25f));
                            }

                            if (target.isDeadOrDying()) {
                                addKill(stack);
                            }
                        }
                    }
                }

                // Enfriamiento de 1.5 segundos (30 ticks) entre cada onda
                player.getCooldowns().addCooldown(this, 30);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        return super.use(level, player, hand);
    }

    @Override
    public void appendHoverText(ItemStack pStack, @Nullable Level pLevel, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        int kills = getKills(pStack);
        float dealt = getDamageDealt(pStack);
        float bonus = getBonusDamage(pStack);
        int level = getSwordLevel(pStack);

        String levelText = (level == 6) ? "6 (NIVEL MÁXIMO)" : String.valueOf(level);
        pTooltipComponents.add(Component.literal("Nivel de Evolución: " + levelText).withStyle(ChatFormatting.GOLD));
        pTooltipComponents.add(Component.literal("Almas Cosechadas: " + kills).withStyle(ChatFormatting.DARK_PURPLE));
        pTooltipComponents.add(Component.literal("Daño Acumulado: " + String.format("%.1f", dealt)).withStyle(ChatFormatting.AQUA));
        pTooltipComponents.add(Component.literal("Daño Evolutivo Extra: +" + String.format("%.2f", bonus)).withStyle(ChatFormatting.RED));

        pTooltipComponents.add(Component.literal("--- Habilidades Desbloqueadas ---").withStyle(ChatFormatting.GRAY));
        pTooltipComponents.add(Component.literal("• Nv.1 (0-15): Robo de Vida (20%)").withStyle(ChatFormatting.GREEN));
        pTooltipComponents.add(Component.literal("• Nv.2 (16-50): Daño y Rango en Área").withStyle(level >= 2 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        pTooltipComponents.add(Component.literal("• Nv.3 (51-80): Filo Ígneo").withStyle(level >= 3 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        pTooltipComponents.add(Component.literal("• Nv.4 (81-100): Onda de Aire [Clic Derecho]").withStyle(level >= 4 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        pTooltipComponents.add(Component.literal("• Nv.5 (101-200): Onda de Aire + Rayos").withStyle(level >= 5 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        pTooltipComponents.add(Component.literal("• Nv.6 (201+): Fuego Infernal + Aspecto Ígneo Máx.").withStyle(level >= 6 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));

        super.appendHoverText(pStack, pLevel, pTooltipComponents, pIsAdvanced);
    }
}
