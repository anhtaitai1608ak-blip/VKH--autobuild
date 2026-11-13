package com.vkh.autobuild;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** VKH Client - addon entry point. */
public final class VKHAutoBuild extends MeteorAddon {
    public static final Category CATEGORY = new Category("VKH");

    @Override
    public void onInitialize() {
        Modules.get().add(new AutoBuildModule());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.vkh.autobuild";
    }
}
