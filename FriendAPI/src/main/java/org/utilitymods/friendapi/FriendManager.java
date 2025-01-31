package org.utilitymods.friendapi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.utilitymods.friendapi.exceptions.ApiFailedException;
import org.utilitymods.friendapi.profiles.Affinity;
import org.utilitymods.friendapi.profiles.Profile;
import org.utilitymods.friendapi.profiles.ProfileFactory;
import org.utilitymods.friendapi.serialization.MapAdapter;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The FriendManager is the main class of the FriendAPI. It is responsible for managing the friends of a player.
 */
public final class FriendManager {

    /**
     * API Version
     */
    private static final String VERSION = "1.1.0";

    /**
     * The Logger.
     */
    private static final Logger LOGGER = LogManager.getLogger("FriendAPI");

    /**
     * The constant INSTANCE of the friend manager.
     */
    private static final FriendManager INSTANCE = new FriendManager();

    /**
     * A map of players' UUIDS to their friend class.
     */
    private final ConcurrentHashMap<UUID, Profile> friends = new ConcurrentHashMap<>();

    /**
     * A map of players' UUIDS to the players they are neutral to, this is not saved and works as a cache;
     */
    private final ConcurrentHashMap<UUID, Profile> neutralCache = new ConcurrentHashMap<>();

    /**
     * Gson Instance
     */
    private final Gson gson = new GsonBuilder().enableComplexMapKeySerialization().registerTypeHierarchyAdapter(Map.class, new MapAdapter()).setPrettyPrinting().create();

    /**
     * TypeToken
     */
    private final Type type = new TypeToken<ConcurrentHashMap<UUID, Profile>>() {}.getType();

    /**
     * Path to Json file
     */
    private final File file = new File(System.getProperty("user.home"), ".friends.json");

    /**
     * Default profile factory
     */
    private ProfileFactory factory = new ProfileFactory();

    /**
     * Instantiates a new Friend manager.
     */
    private FriendManager() {
        long start = System.currentTimeMillis();
        LOGGER.info("Using FriendAPI " + VERSION);

        load();
        Runtime.getRuntime().addShutdownHook(new Thread(this::save));

        LOGGER.info("FriendAPI started in " + (System.currentTimeMillis() - start) + "ms");

    }

    /**
     * Gets the instance of the friend manager.
     */
    public static FriendManager getInstance() {
        if (INSTANCE == null)
            throw new RuntimeException("FriendAPI accessed to early");
        return INSTANCE;
    }

    /**
     * Loads friends.json into FRIENDS hashmap.
     */
    public void load() {
        try {
            if (!file.exists()) {
                save();
            }
            if (file.exists()) {
                Reader reader = Files.newBufferedReader(file.toPath());
                ConcurrentHashMap<UUID, Profile> tempList = gson.fromJson(reader, type);
                if (tempList != null) friends.putAll(tempList);
                reader.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.fatal("Failed to load " + file.getAbsolutePath() + "!");
        }
    }

    /**
     * Saves the FRIENDS hashmap into friends.json.
     */
    public void save() {
        try {
            OutputStreamWriter output = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            output.write(gson.toJson(friends));
            output.close();
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.fatal("Failed to save \"" + file.getAbsolutePath() + "\"!");
        }
    }

    /**
     * Gets friend map copy.
     *
     * @return a copy of the FRIENDS hashmap
     */
    @NotNull
    public Map<UUID, Profile> getFriendMapCopy() {
        return Collections.unmodifiableMap(friends);
    }

    /**
     * Gets a list with only your friends profiles
     *
     * @return list with only friends profiles
     */
    @NotNull
    public List<Profile> getOnlyFriendsProfiles() {
        return friends.values().stream().filter(Profile::isFriend).collect(Collectors.toList());
    }

    /**
     * Gets a only your friends and enemies profiles
     *
     * @return list with only friends and enemies profiles
     */
    @NotNull
    public Collection<Profile> getOnlyAllProfiles() {
        return new HashSet<>(getFriendMapCopy().values());
    }

    /**
     * Gets a friend from the FRIENDS hashmap.
     *
     * @param uuid the uuid of the player to search for
     * @return the friend
     */
    @NotNull
    public Profile getFriend(@NotNull UUID uuid) {
        if (friends.containsKey(uuid)) {
            return friends.get(uuid);
        } else {
            return neutralCache.computeIfAbsent(uuid, k -> {
                try {
                    return factory.createProfile(k);
                } catch (ApiFailedException e) {
                   return factory.createProfile("empty", uuid);
                }
            });
        }
    }

    /**
     * Add or replace a friend in the FRIENDS hashmap.
     *
     * @param profile the profile of the player to register
     * @return the newly created friend
     */
    public Profile addFriend(Profile profile) {
        return friends.compute(profile.getUUID(), ((uuid, profile1) -> profile1 = profile));
    }

    /**
     * Removes friend from the FRIENDS hashmap.
     *
     * @param uuid the uuid of the friend to remove
     */
    public void removeFriend(@NotNull UUID uuid) {
        Profile profile = friends.get(uuid);
        if (profile != null) {
            friends.remove(uuid);
            profile.setAffinity(Affinity.NEUTRAL);
            neutralCache.compute(uuid, ((uuid1, baseProfile) -> baseProfile = profile));
        }
    }

    /**
     * Gets friend type from the FRIENDS hashmap.
     *
     * @param uuid the uuid
     * @return the registered friend type
     */
    @NotNull
    public Affinity getAffinity(@NotNull UUID uuid) {
        return getFriend(uuid).getAffinity();
    }

    /**
     * Checks if player with specified UUID is a friend according to the FRIENDS hashmap.
     *
     * @param uuid the uuid of the player
     * @return whether the player queried is a registered friend
     */
    public boolean isFriend(@NotNull UUID uuid) {
        return getAffinity(uuid) == Affinity.FRIEND;
    }

    /**
     * Checks if player with specified UUID is an enemy according to the FRIENDS hashmap.
     *
     * @param uuid the uuid of the player
     * @return whether the player queried is a registered enemy
     */
    public boolean isEnemy(@NotNull UUID uuid) {
        return getAffinity(uuid) == Affinity.ENEMY;
    }

    /**
     * Checks if player with specified UUID is neutral or unregistered in the FRIENDS hashmap.
     *
     * @param uuid the uuid of the player
     * @return whether the player queried is neutral or unregistered
     */
    public boolean isNeutral(@NotNull UUID uuid) {
        return getAffinity(uuid).type < 2;
    }

    /**
     * Attempts to add all friends from a name based friend list
     *
     * @param nameList List of the usernames you want to add to the friend list
     */
    public void migrateFromNameList(List<String> nameList) {
        nameList.forEach(name -> {
            try {
                addFriend(getFactory().createFriend(name));
            } catch (ApiFailedException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Attempts to add all friends from a uuid based friend list
     *
     * @param nameList List of player uuids you want to add to the friend list
     */
    public void migrateFromUuidList(List<UUID> nameList) {
        nameList.forEach(uuid -> {
            try {
                addFriend(getFactory().createFriend(uuid));
            } catch (ApiFailedException e) {
                e.printStackTrace();
            }
        });
    }

    public ProfileFactory getFactory() {
        return factory;
    }

    public void setFactory(ProfileFactory factory) {
        this.factory = factory;
    }

}
