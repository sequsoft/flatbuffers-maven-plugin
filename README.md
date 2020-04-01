# Flatbuffers maven plugin

A maven plugin that can be used to generate Java source files from Flatbuffers schema files.
It works by getting a specified tagged version of the flatbuffers source code from google's repositories,
storing it to a directory `~/.flatbuffers`, checking out the required tag, compiling it to obtain the `flatc`
executable and then using it to generate code from the schemas in the `generate-sources` compilation maven phase.

It can also be used in a simpler way with a `flatc` executable manually installed to the `.flatbuffers` directory.

## Usage

Use the plugin as below in your maven build:

```
    <build>
        <plugins>
            <plugin>
                <groupId>com.sequsoft.maven.plugins</groupId>
                <artifactId>flatbuffers-maven-plugin</artifactId>
                <version>0.0.1-SNAPSHOT</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>compile-flatbuffers</goal>
                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <version>1.12.0</version>
                    <sources>
                        <source>${basedir}/src/main/resources/F_HouseGroup.fbs</source>
                    </sources>
                    <generators>
                        <generator>all</generator>
                    </generators>
                    <includes>
                        <include>${basedir}/src/main/resources</include>
                    </includes>
                    <destination>${basedir}/target/generated-sources</destination>
                </configuration>
            </plugin>
        </plugins>
    </build>
```

Available parameters for the `configuration` section are:

- `version` - the tagged version of flatbuffers to use, e.g. `1.12.0` (without `v` at the front).
- `flatbuffersUrl` - the url to the flatbuffers repository, useful if you have your own version.
- `sources` - a list of `source` values, the schema files
- `includes` - a list of `include`values for directories to search for schema includes
- `destination` - the destination directory into which the generated java code files should be placed
- `generators` a list of `generator` options, the same as those available in flatc, from the list:
  - `all` - generate all files
  - `mutable` - generate code with mutator methods
  - `nullable`- generate `@Nullable` attributes on method parameters
  - `generated` - generate `@Generated` attribute on all classes

## Operation

When the plugin runs, it first checks for the existence of a compiled flatc file in the `~/.flatbuffers` directory.
If this file exists and reports it is the required version, then the generation of sources proceeds immediately.

If the flatc version is incorrect, it is ensured that the `.flatbuffers` directory is a Git repository retrieved from
github. It is ensured that it is checked out at the right tag version and then flatc is compiled. Once this is done,
the generation of sources can proceed.

Note that retrieval and compilation of the executable is useful so that the plugin will work on multiple linux platforms
(and on OSX).

If you already have the compiled flatc executable, it is simply enough to place it in the `.flatbuffers` directory and it
will be used with no further effort.

## Pre-requisites

Your platform must be able to retrieve and build the flatbuffers code. Generally this requires the additional tools beyond
what would be available on a purely Java building platform, namely: `cmake`, `make` and a c++ compiler.

For example on _CentOS 7_ it was adequate to install: `cmake`, `gcc` and `gcc-c++`.

## Supported platforms

Tested on:

- linux, e.g. _CentosOS_, _Ubuntu_
- OSX
- Windows 10 (with `flatc.exe` manually installed)

## Known issues

I haven't been able to make this work on Windows as I haven't been able to get the `cmake` process to work.
If `flatc` is manually installed and you want to change the version, delete the `.flatbuffers` before running the plugin.