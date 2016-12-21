/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.java.compile

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class IncrementalJavaCompileIntegrationTest extends AbstractIntegrationSpec {

    def "recompiles source when properties change"() {
        given:
        file('src/main/java/Test.java') << 'public class Test{}'
        buildFile << '''
            apply plugin: 'java'
            sourceCompatibility = 1.7
            compileJava.options.debug = true
        '''.stripIndent()

        when:
        succeeds ':compileJava'

        then:
        executedAndNotSkipped ':compileJava'

        when:
        buildFile << 'sourceCompatibility = 1.6\n'
        succeeds ':compileJava'

        then:
        executedAndNotSkipped ':compileJava'

        when:
        buildFile << 'compileJava.options.debug = false\n'
        succeeds ':compileJava'

        then:
        executedAndNotSkipped ':compileJava'

        when:
        succeeds ':compileJava'

        then:
        skipped ':compileJava'
    }

    def "recompiles dependent classes"() {
        given:
        file('src/main/java/IPerson.java') << 'interface IPerson { String getName(); }'
        file('src/main/java/Person.java') << 'class Person implements IPerson { public String getName() { return "name"; } }'
        buildFile << 'apply plugin: "java"\n'

        expect:
        succeeds 'classes'

        when: 'update interface, compile should fail'
        file('src/main/java/IPerson.java').text = 'interface IPerson { String getName(); String getAddress(); }'

        then:
        def failure = fails 'classes'
        failure.assertHasDescription "Execution failed for task ':compileJava'."
    }

    def "recompiles dependent classes across project boundaries"() {
        given:
        file('lib/src/main/java/IPerson.java') << 'interface IPerson { String getName(); }'
        file('app/src/main/java/Person.java') << 'class Person implements IPerson { public String getName() { return "name"; } }'
        settingsFile << 'include "lib", "app"'
        buildFile << '''
            subprojects {
                apply plugin: 'java'
            }            
            project(':app') {
                dependencies {
                    compile project(':lib')
                }
            }
        '''.stripIndent()

        expect:
        succeeds 'app:classes'

        when: 'update interface, compile should fail'
        file('lib/src/main/java/IPerson.java').text = 'interface IPerson { String getName(); String getAddress(); }'

        then:
        def failure = fails 'app:classes'
        failure.assertHasDescription "Execution failed for task ':app:compileJava'."
    }

    def "recompiles dependent classes when using ant depend"() {
        given:
        file('src/main/java/IPerson.java') << 'interface IPerson { String getName(); }'
        file('src/main/java/Person.java') << 'class Person implements IPerson { public String getName() { return "name"; } }'
        buildFile << '''
            apply plugin: 'java'
            compileJava.options.depend()
        '''.stripIndent()

        expect:
        executer.expectDeprecationWarning()
        succeeds 'classes'

        and: 'file system time stamp may not see change without this wait'
        sleep 1000

        when: 'update interface, compile should fail because depend deletes old class'
        file('src/main/java/IPerson.java').text = 'interface IPerson { String getName(); String getAddress(); }'

        then:
        executer.expectDeprecationWarning()
        def failure = fails 'classes'
        failure.assertHasDescription "Execution failed for task ':compileJava'."

        and: 'assert that dependency caching is on'
        file('build/dependency-cache/dependencies.txt').assertExists();
    }

    def "task outcome is UP-TO-DATE when no recompilation necessary"() {
        given:
        file('src/main/java/Something.java') << 'public class Something {}'
        file('input.txt') << 'original content'
        buildFile << '''
            plugins {
                id 'java'
            }
            tasks.withType(JavaCompile) {
                it.options.incremental = true
                it.inputs.file 'input.txt'
            }
        '''.stripIndent()

        when:
        succeeds ':compileJava'

        then:
        executedAndNotSkipped ':compileJava'

        when:
        file('input.txt').text = 'second run, triggers task execution, but no recompilation is necessary'

        then:
        succeeds ':compileJava', '--debug'

        and:
        result.output.contains "Executing actions for task ':compileJava'."
        result.output.contains ":compileJava UP-TO-DATE"
    }
}
