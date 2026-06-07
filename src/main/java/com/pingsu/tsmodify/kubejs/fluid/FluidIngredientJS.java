package com.pingsu.tsmodify.kubejs.fluid;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.fluid.FluidStack;
import dev.latvian.mods.kubejs.fluid.FluidLike;
import dev.latvian.mods.kubejs.fluid.FluidStackJS;
import dev.latvian.mods.kubejs.fluid.InputFluid;
import dev.latvian.mods.kubejs.util.MapJS;
import dev.latvian.mods.kubejs.util.UtilsJS;
import dev.latvian.mods.rhino.Wrapper;
import dev.latvian.mods.rhino.mod.util.JsonSerializable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.Map;
import java.util.Objects;

public final class FluidIngredientJS implements InputFluid, JsonSerializable {
    private static final String[] TAG_KEYS = {"tag", "fluidTag", "fluid_tag"};
    private static final String[] VALUE_KEYS = {"value", "ingredient"};

    private final ResourceLocation tag;
    private final FluidStackJS stack;
    private final long amount;

    private FluidIngredientJS(ResourceLocation tag, long amount) {
        this.tag = Objects.requireNonNull(tag, "tag");
        this.stack = null;
        this.amount = amount;
    }

    private FluidIngredientJS(FluidStackJS stack) {
        this.tag = null;
        this.stack = Objects.requireNonNull(stack, "stack");
        this.amount = stack.kjs$getAmount();
    }

    public static FluidIngredientJS of(Object value) {
        return ofInternal(value, null);
    }

    public static FluidIngredientJS of(Object value, long amount) {
        return ofInternal(value, amount);
    }

    public static FluidIngredientJS tag(Object tag) {
        return tag(tag, FluidStack.bucketAmount());
    }

    public static FluidIngredientJS tag(Object tag, long amount) {
        return new FluidIngredientJS(parseTag(tag), amount);
    }

    public static boolean isFluidIngredient(Object value) {
        value = unwrap(value);

        if (value instanceof FluidIngredientJS || value instanceof TagKey<?>) {
            return true;
        }

        if (value instanceof JsonElement json) {
            if (json.isJsonPrimitive()) {
                return isTagString(json.getAsString());
            }

            return json.isJsonObject() && hasTagKey(json.getAsJsonObject());
        }

        if (value instanceof CharSequence sequence) {
            return isTagString(sequence.toString());
        }

        Map<?, ?> map = MapJS.of(value);
        return map != null && (findMapValue(map, TAG_KEYS) != null || isNestedTagValue(map));
    }

    public ResourceLocation getTag() {
        return tag;
    }

    public String getTagId() {
        return tag == null ? "" : tag.toString();
    }

    public long getAmount() {
        return kjs$getAmount();
    }

    @Override
    public long kjs$getAmount() {
        return stack == null ? amount : stack.kjs$getAmount();
    }

    @Override
    public FluidIngredientJS kjs$copy(long amount) {
        if (stack != null) {
            return new FluidIngredientJS(stack.withAmount(amount));
        }

        return new FluidIngredientJS(tag, amount);
    }

    public FluidIngredientJS withAmount(long amount) {
        return kjs$copy(amount);
    }

    @Override
    public boolean matches(FluidLike other) {
        if (stack != null) {
            if (other instanceof FluidIngredientJS ingredient) {
                if (ingredient.stack != null) {
                    return stack.matches(ingredient.stack);
                }

                return ingredient.matches(stack);
            }

            return stack.matches(other);
        }

        if (other instanceof FluidIngredientJS ingredient) {
            if (ingredient.tag != null) {
                return tag.equals(ingredient.tag);
            }

            return ingredient.stack != null && matches(ingredient.stack);
        }

        if (other instanceof FluidStackJS stack) {
            return stack.hasTag(tag);
        }

        FluidStackJS stack = FluidStackJS.of(other);
        return !stack.kjs$isEmpty() && stack.hasTag(tag);
    }

    public boolean test(Object value) {
        FluidIngredientJS fluid = ofInternal(value, null);
        return matches(fluid);
    }

    public JsonObject toJson() {
        return toJson("tag", "amount");
    }

    public JsonObject toJson(String tagKey) {
        return toJson(tagKey, "amount");
    }

    public JsonObject toJson(String tagKey, String amountKey) {
        if (stack != null) {
            return stack.toJson();
        }

        JsonObject json = new JsonObject();
        json.addProperty(tagKey, tag.toString());
        json.addProperty(amountKey, amount);
        return json;
    }

    @Override
    public JsonElement toJsonJS() {
        return toJson();
    }

    public JsonObject toNestedJson() {
        return toNestedJson("ingredient", "tag", "amount");
    }

    public JsonObject toNestedJson(String ingredientKey) {
        return toNestedJson(ingredientKey, "tag", "amount");
    }

    public JsonObject toNestedJson(String ingredientKey, String tagKey, String amountKey) {
        JsonObject json = new JsonObject();
        json.addProperty(amountKey, kjs$getAmount());
        json.add(ingredientKey, toTagJson(tagKey));
        return json;
    }

    public JsonObject toTagJson() {
        return toTagJson("tag");
    }

    public JsonObject toTagJson(String tagKey) {
        if (stack != null) {
            return stack.toJson();
        }

        JsonObject json = new JsonObject();
        json.addProperty(tagKey, tag.toString());
        return json;
    }

    @Override
    public String toString() {
        if (stack != null) {
            return stack.toString();
        }

        return "FluidIngredient.of('#" + tag + "', " + amount + ")";
    }

    private static FluidIngredientJS ofInternal(Object value, Long amountOverride) {
        value = unwrap(value);

        if (value instanceof FluidIngredientJS ingredient) {
            return amountOverride == null ? ingredient : ingredient.withAmount(amountOverride);
        }

        if (value instanceof TagKey<?> tagKey) {
            return tag(tagKey.location(), amountOrDefault(amountOverride, FluidStack.bucketAmount()));
        }

        if (value instanceof JsonElement json) {
            return fromJson(json, amountOverride);
        }

        if (value instanceof CharSequence sequence && isTagString(sequence.toString())) {
            return tag(sequence.toString(), amountOrDefault(amountOverride, FluidStack.bucketAmount()));
        }

        Map<?, ?> map = MapJS.of(value);
        if (map != null) {
            FluidIngredientJS mapped = fromMap(map, amountOverride);
            if (mapped != null) {
                return mapped;
            }
        }

        if (amountOverride == null) {
            return new FluidIngredientJS(FluidStackJS.of(value));
        }

        FluidStackJS stack = FluidStackJS.of(value);
        stack.setAmount(amountOverride);
        return new FluidIngredientJS(stack);
    }

    private static FluidIngredientJS fromJson(JsonElement json, Long amountOverride) {
        if (json.isJsonPrimitive() && isTagString(json.getAsString())) {
            return tag(json.getAsString(), amountOrDefault(amountOverride, FluidStack.bucketAmount()));
        }

        if (!json.isJsonObject()) {
            return amountOverride == null ? new FluidIngredientJS(FluidStackJS.fromJson(json)) : ofInternal(json.getAsString(), amountOverride);
        }

        JsonObject object = json.getAsJsonObject();
        JsonElement tagValue = findJsonValue(object, TAG_KEYS);
        if (tagValue != null) {
            long amount = amountOrDefault(amountOverride, amountFromJson(object, FluidStack.bucketAmount()));
            return tag(tagValue, amount);
        }

        JsonElement nested = findJsonValue(object, VALUE_KEYS);
        if (nested != null && isFluidIngredient(nested)) {
            long amount = amountOrDefault(amountOverride, amountFromJson(object, FluidStack.bucketAmount()));
            return ofInternal(nested, amount);
        }

        return amountOverride == null ? new FluidIngredientJS(FluidStackJS.fromJson(json)) : ofInternal(FluidStackJS.fromJson(json), amountOverride);
    }

    private static FluidIngredientJS fromMap(Map<?, ?> map, Long amountOverride) {
        Object tagValue = findMapValue(map, TAG_KEYS);
        if (tagValue != null) {
            long amount = amountOrDefault(amountOverride, amountFromMap(map, FluidStack.bucketAmount()));
            return tag(tagValue, amount);
        }

        Object nested = findMapValue(map, VALUE_KEYS);
        if (nested != null && isFluidIngredient(nested)) {
            long amount = amountOrDefault(amountOverride, amountFromMap(map, FluidStack.bucketAmount()));
            return ofInternal(nested, amount);
        }

        return null;
    }

    private static boolean isNestedTagValue(Map<?, ?> map) {
        Object nested = findMapValue(map, VALUE_KEYS);
        return nested != null && isFluidIngredient(nested);
    }

    private static ResourceLocation parseTag(Object value) {
        value = unwrap(value);

        if (value instanceof TagKey<?> tagKey) {
            return tagKey.location();
        }

        String id = asString(value).trim();
        if (id.startsWith("#")) {
            id = id.substring(1);
        }

        return ResourceLocation.parse(id);
    }

    private static long amountFromJson(JsonObject object, long fallback) {
        JsonElement amount = object.get("amount");
        if (amount == null || amount.isJsonNull()) {
            amount = object.get("count");
        }

        return amount == null || amount.isJsonNull() ? fallback : UtilsJS.parseLong(amount.getAsString(), fallback);
    }

    private static long amountFromMap(Map<?, ?> map, long fallback) {
        Object amount = map.get("amount");
        if (amount == null) {
            amount = map.get("count");
        }

        return amount == null ? fallback : UtilsJS.parseLong(amount, fallback);
    }

    private static long amountOrDefault(Long amount, long fallback) {
        return amount == null ? fallback : amount;
    }

    private static boolean hasTagKey(JsonObject object) {
        return findJsonValue(object, TAG_KEYS) != null;
    }

    private static JsonElement findJsonValue(JsonObject object, String[] keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull()) {
                return value;
            }
        }

        return null;
    }

    private static Object findMapValue(Map<?, ?> map, String[] keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private static boolean isTagString(String value) {
        return value.trim().startsWith("#");
    }

    private static String asString(Object value) {
        value = unwrap(value);

        if (value instanceof JsonElement json) {
            return json.isJsonPrimitive() ? json.getAsString() : json.toString();
        }

        return String.valueOf(value);
    }

    private static Object unwrap(Object value) {
        while (value instanceof Wrapper wrapper) {
            value = wrapper.unwrap();
        }

        return value;
    }
}
