/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.util.indexing;

import com.intellij.ide.plugins.PluginUtil;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.IntObjectMap;
import gnu.trove.THashMap;
import gnu.trove.TObjectIntHashMap;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author Eugene Zhuravlev
 */
public class ID<K, V> extends IndexId<K,V> {
  private static final Logger LOG = Logger.getInstance(ID.class);
  private static final IntObjectMap<ID<?, ?>> ourRegistry = ContainerUtil.createConcurrentIntObjectMap();
  private static final TObjectIntHashMap<String> ourNameToIdRegistry = new TObjectIntHashMap<>();

  private static final Map<ID<?, ?>, PluginId> ourIdToPluginId = Collections.synchronizedMap(new THashMap<>());
  private static final Map<ID<?, ?>, Throwable> ourIdToRegistrationStackTrace = Collections.synchronizedMap(new THashMap<>());
  static final int MAX_NUMBER_OF_INDICES = Short.MAX_VALUE;

  private final short myUniqueId;

  static {
    Path indices = getEnumFile();
    try {
      TObjectIntHashMap<String> nameToIdRegistry = new TObjectIntHashMap<>();
      List<String> lines = Files.readAllLines(indices, Charset.defaultCharset());
      for (int i = 0; i < lines.size(); i++) {
        String name = lines.get(i);
        nameToIdRegistry.put(name, i + 1);
      }

      synchronized (ourNameToIdRegistry) {
        ourNameToIdRegistry.ensureCapacity(nameToIdRegistry.size());
        nameToIdRegistry.forEachEntry((name, index) -> {
          ourNameToIdRegistry.put(name, index);
          return true;
        });
      }
    }
    catch (IOException e) {
      synchronized (ourNameToIdRegistry) {
        ourNameToIdRegistry.clear();
        writeEnumFile();
      }
    }
  }

  @NotNull
  private static Path getEnumFile() {
    return PathManager.getIndexRoot().toPath().resolve("indices.enum");
  }

  @ApiStatus.Internal
  protected ID(@NotNull String name, @Nullable PluginId pluginId) {
    super(name);
    myUniqueId = stringToId(name);

    final ID old = ourRegistry.put(myUniqueId, this);
    assert old == null : "ID with name '" + name + "' is already registered";

    PluginId oldPluginId = ourIdToPluginId.put(this, pluginId);
    assert oldPluginId == null : "ID with name '" + name + "' is already registered in " + oldPluginId + " but current caller is " + pluginId;

    ourIdToRegistrationStackTrace.put(this, new Throwable());
  }

  private static short stringToId(@NotNull String name) {
    synchronized (ourNameToIdRegistry) {
      if (ourNameToIdRegistry.containsKey(name)) {
        return (short)ourNameToIdRegistry.get(name);
      }

      int n = ourNameToIdRegistry.size() + 1;
      assert n <= MAX_NUMBER_OF_INDICES : "Number of indices exceeded: "+n;

      ourNameToIdRegistry.put(name, n);
      writeEnumFile();
      return (short)n;
    }
  }

  static void reinitializeDiskStorage() {
    synchronized (ourNameToIdRegistry) {
      writeEnumFile();
    }
  }

  private static void writeEnumFile() {
    try {
      final String[] names = new String[ourNameToIdRegistry.size()];
      ourNameToIdRegistry.forEachEntry((key, value) -> {
        names[value - 1] = key;
        return true;
      });

      Files.write(getEnumFile(), Arrays.asList(names), Charset.defaultCharset());
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  public static synchronized <K, V> ID<K, V> create(@NonNls @NotNull String name) {
    PluginId pluginId = getCallerPluginId();
    final ID<K, V> found = findByName(name, true, pluginId);
    return found == null ? new ID<>(name, pluginId) : found;
  }

  @Nullable
  public static <K, V> ID<K, V> findByName(@NotNull String name) {
    return findByName(name, false, null);
  }

  @ApiStatus.Internal
  @Nullable
  protected static <K, V> ID<K, V> findByName(@NotNull String name,
                                              boolean checkCallerPlugin,
                                              @Nullable PluginId requiredPluginId) {
    ID<K, V> id = (ID<K, V>)findById(stringToId(name));
    if (checkCallerPlugin && id != null) {
      PluginId actualPluginId = ourIdToPluginId.get(id);

      String actualPluginIdStr = actualPluginId == null ? null : actualPluginId.getIdString();
      String requiredPluginIdStr = requiredPluginId == null ? null : requiredPluginId.getIdString();

      if (!Comparing.equal(actualPluginIdStr, requiredPluginIdStr)) {
        Throwable registrationStackTrace = ourIdToRegistrationStackTrace.get(id);
        String message = "ID with name '" + name +
                         "' requested for plugin " + requiredPluginIdStr +
                         " but registered for " + actualPluginIdStr + (registrationStackTrace == null ? " registration stack trace: " : "");
        if (registrationStackTrace != null) {
          throw new AssertionError(message, registrationStackTrace);
        } else {
          throw new AssertionError(message);
        }
      }
    }
    return id;
  }

  @Override
  public int hashCode() {
    return myUniqueId;
  }

  public int getUniqueId() {
    return myUniqueId;
  }

  public static ID<?, ?> findById(int id) {
    return ourRegistry.get(id);
  }

  @ApiStatus.Internal
  @Nullable
  protected static PluginId getCallerPluginId() {
    return PluginUtil.getInstance().getCallerPlugin(4);
  }

  @ApiStatus.Internal
  public synchronized static void unloadId(@NotNull ID<?, ?> id) {
    LOG.assertTrue(id.equals(ourRegistry.remove(id.getUniqueId())));
    ourIdToPluginId.remove(id);
    ourIdToRegistrationStackTrace.remove(id);
  }

  public static void dump() {
    Logger.getInstance(ID.class).info("ID registry: " + ourRegistry.toString());
  }
}
