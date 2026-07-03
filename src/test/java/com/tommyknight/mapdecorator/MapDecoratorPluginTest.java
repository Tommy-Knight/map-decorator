package com.tommyknight.mapdecorator;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class MapDecoratorPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(MapDecoratorPlugin.class);
		RuneLite.main(args);
	}
}
