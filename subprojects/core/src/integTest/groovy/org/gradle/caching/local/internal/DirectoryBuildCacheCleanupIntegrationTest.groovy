/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.local.internal

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.LocalBuildCacheFixture
import org.gradle.test.fixtures.file.TestFile
import spock.lang.Ignore
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

class DirectoryBuildCacheCleanupIntegrationTest extends AbstractIntegrationSpec implements LocalBuildCacheFixture {
    private final static int MAX_CACHE_SIZE = 5 // MB
    def setup() {
        settingsFile << """
            buildCache {
                local {
                    targetSizeInMB = ${MAX_CACHE_SIZE}
                }
            }
        """
        def bytes = new byte[1024*1024]
        new Random().nextBytes(bytes)
        file("output.txt").bytes = bytes

        buildFile << """
            @CacheableTask
            class CustomTask extends DefaultTask {
                @OutputFile File outputFile = new File(temporaryDir, "output.txt")
                @Input String run = project.findProperty("run") ?: ""
                @TaskAction 
                void generate() {
                    logger.warn("Run " + run)
                    project.copy {
                        from("output.txt")
                        into temporaryDir
                    }
                }
            }
            
            task cacheable(type: CustomTask) {
                description = "Generates a 1MB file"
            }
        """
    }

    def "cleans up when over target"() {
        when:
        runMultiple(MAX_CACHE_SIZE*2)
        then:
        def originalList = listCacheFiles()
        // build cache hasn't been cleaned yet
        originalList.size() == MAX_CACHE_SIZE*2
        calculateCacheSize(originalList) >= MAX_CACHE_SIZE

        when:
        cleanupBuildCacheNow()
        and:
        withBuildCache().succeeds("cacheable")
        then:
        def newList = listCacheFiles()
        newList.size() == MAX_CACHE_SIZE-1
        // Cache should be under the target size
        calculateCacheSize(newList) <= MAX_CACHE_SIZE
    }

    def "cleans up the oldest entries first"() {
        when:
        runMultiple(MAX_CACHE_SIZE*2)
        then:
        def originalList = listCacheFiles()
        // build cache hasn't been cleaned yet
        originalList.size() == MAX_CACHE_SIZE*2
        calculateCacheSize(originalList) >= MAX_CACHE_SIZE

        when:
        cleanupBuildCacheNow()
        def timeNow = System.currentTimeMillis()
        originalList.eachWithIndex { cacheEntry, index ->
            // Set the lastModified time for each cache entry back monotonically increasing days
            // so the first cache entry was accessed now-0 days
            // the next now-1 days, etc.
            cacheEntry.lastModified = timeNow - TimeUnit.DAYS.toMillis(index)
        }
        and:
        withBuildCache().succeeds("cacheable")
        then:
        def newList = listCacheFiles()
        newList.size() == MAX_CACHE_SIZE-1

        // All of the old cache entries should have been deleted first
        def ageOfCacheEntries = newList.collect { cacheEntry ->
            (cacheEntry.lastModified() - timeNow)
        }
        def oldestCacheEntry = TimeUnit.DAYS.toMillis(newList.size())
        ageOfCacheEntries.every { it < oldestCacheEntry }

        // Cache should be under the target size
        calculateCacheSize(newList) <= MAX_CACHE_SIZE
    }

    def "cleans up based on LRU"() {
        when:
        runMultiple(MAX_CACHE_SIZE*2)
        then:
        def originalList = listCacheFiles()
        // build cache hasn't been cleaned yet
        originalList.size() == MAX_CACHE_SIZE*2
        calculateCacheSize(originalList) >= MAX_CACHE_SIZE

        when:
        def oldTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(100)
        originalList.each { cacheEntry ->
            cacheEntry.lastModified = oldTime
        }
        and:
        withBuildCache().succeeds("cacheable", "-Prun=2")
        withBuildCache().succeeds("cacheable", "-Prun=4")
        withBuildCache().succeeds("cacheable", "-Prun=6")
        then:
        def recentlyUsed = originalList.findAll {
            it.lastModified() > oldTime
        }
        recentlyUsed.size() == 3

        when:
        cleanupBuildCacheNow()
        and:
        withBuildCache().succeeds("cacheable")

        then:
        def newList = listCacheFiles()
        newList.size() == 4
        newList.containsAll(recentlyUsed)
    }

    def "does not cleanup on every build"() {
        when:
        runMultiple(MAX_CACHE_SIZE*2)
        then:
        def originalList = listCacheFiles()
        // build cache hasn't been cleaned yet
        originalList.size() == MAX_CACHE_SIZE*2
        calculateCacheSize(originalList) >= MAX_CACHE_SIZE

        when:
        cleanupBuildCacheNow()
        and:
        withBuildCache().succeeds("cacheable")
        then:
        def newList = listCacheFiles()
        newList.size() == MAX_CACHE_SIZE-1
        // Cache should be under the target size
        calculateCacheSize(newList) <= MAX_CACHE_SIZE
        def lastCleanedTime = gcFile().lastModified()

        // build cache shouldn't clean up again
        when:
        runMultiple(MAX_CACHE_SIZE)
        then:
        // the exact count depends on exactly which cache entries were cleaned above
        // which depends on file system ordering/time resolution
        listCacheFiles().size() >= MAX_CACHE_SIZE
        lastCleanedTime == gcFile().lastModified()
    }

    def "build cache leaves files that aren't cache entries"() {
        when:
        runMultiple(MAX_CACHE_SIZE*2)
        and:
        cacheDir.file("DO_NOT_DELETE").bytes = file("output.txt").bytes
        then:
        def originalList = listCacheFiles()
        // build cache hasn't been cleaned yet
        originalList.size() == MAX_CACHE_SIZE*2
        calculateCacheSize(originalList) >= MAX_CACHE_SIZE

        when:
        cleanupBuildCacheNow()
        and:
        withBuildCache().succeeds("cacheable")
        then:
        cacheDir.file("DO_NOT_DELETE").assertExists()
        gcFile().assertExists()
        cacheDir.file("cache.properties").assertExists()
        calculateCacheSize(listCacheFiles()) <= MAX_CACHE_SIZE
    }

    def "build cache cleanup handles broken links in cache directory"() {
        when:
        runMultiple(MAX_CACHE_SIZE*2)
        and:
        def target = cacheDir.file("target")
        def link = cacheDir.file("0"*32)
        link.createLink(target)
        link.lastModified = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60)
        target.delete() // break the link

        then:
        def originalList = listCacheFiles()
        // build cache hasn't been cleaned yet and has an extra "link" entry
        originalList.size() == MAX_CACHE_SIZE*2 + 1
        calculateCacheSize(originalList) >= MAX_CACHE_SIZE

        when:
        cleanupBuildCacheNow()
        and:
        withBuildCache().succeeds("cacheable")
        then:
        calculateCacheSize(listCacheFiles()) <= MAX_CACHE_SIZE
    }

    @Unroll
    def "produces reasonable message when cache is too small (#size)"() {
        settingsFile << """
            buildCache {
                local {
                    targetSizeInMB = ${size}
                }
            }
        """
        expect:
        fails("help")
        result.error.contains("Directory build cache needs to have at least 1 MB of space but more space is useful.")

        where:
        size << [-1, 0]
    }

    def "build cache cleanup is triggered after 7 days"() {
        def messageRegex = /Build cache \(.+\) cleaned up in .+ secs./

        when:
        withBuildCache().succeeds("cacheable")
        then:
        listCacheFiles().size() == 1
        def originalCheckTime = gcFile().lastModified()
        // Set the time back 1 day
        gcFile().setLastModified(originalCheckTime - TimeUnit.DAYS.toMillis(1))
        def lastCleanupCheck = gcFile().lastModified()

        // One day isn't enough to trigger
        when:
        withBuildCache().succeeds("cacheable", "-i")
        then:
        result.output.readLines().every {
            !it.matches(messageRegex)
        }
        gcFile().lastModified() == lastCleanupCheck

        // 7 days is enough to trigger
        when:
        gcFile().setLastModified(originalCheckTime - TimeUnit.DAYS.toMillis(7))
        and:
        withBuildCache().succeeds("cacheable", "-i")
        then:
        result.output.readLines().any {
            it.matches(messageRegex)
        }
        gcFile().lastModified() > lastCleanupCheck

        // More than 7 days is enough to trigger
        when:
        gcFile().setLastModified(originalCheckTime - TimeUnit.DAYS.toMillis(100))
        and:
        withBuildCache().succeeds("cacheable", "-i")
        then:
        result.output.readLines().any {
            it.matches(messageRegex)
        }
        gcFile().lastModified() > lastCleanupCheck
    }


    @Ignore
    def "buildSrc does not try to clean build cache"() {
        file("buildSrc/settings.gradle").text = settingsFile.text
        file("buildSrc/build.gradle").text = buildFile.text
        file("buildSrc/build.gradle") << """
            build.dependsOn cacheable
        """
    }

    @Ignore
    def "composite builds do not try to clean build cache"() {

    }

    private static long calculateCacheSize(List<TestFile> originalList) {
        def cacheSize = originalList.inject(0L) { acc, val ->
            val.size() + acc
        }
        cacheSize / 1024 / 1024
    }

    void runMultiple(int times) {
        (1..times).each {
            withBuildCache().succeeds("cacheable", "-Prun=${it}")
        }
    }
}