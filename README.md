# BIGCAT (working title)

[![Build Status](https://travis-ci.org/ssinhaleite/bigcat.svg?branch=javafx-generic-listeners)](https://travis-ci.org/ssinhaleite/bigcat)

![screenshot](https://raw.githubusercontent.com/ssinhaleite/bigcat/javafx-generic-listeners/img/bigcat-20171207.png)

## Dependences

* java
```shell
sudo apt install default-jre default-jdk
```

* maven (ubuntu):
```shell
sudo apt install maven
```

* javafx (ubuntu):

```shell
sudo apt install openjfx
```

* zeromq (ubuntu):

```shell
sudo apt install libzmq3-dev
```
* [jzmq](https://github.com/zeromq/jzmq)

## Compile

run:

```shell
mvn clean install
```

or, to generate a "fat jar" with all dependencies added, run:

```shell
mvn clean compile assembly:single
```

## Run

```shell
java -Xmx16G -XX:+UseConcMarkSweepGC -jar target/bigcat-0.0.3-SNAPSHOT-jar-with-dependencies.jar
```

or you can download a compiled fat jar from [here - updated on 06.12.2017](https://www.dropbox.com/s/rlra6qg2uqog45v/bigcat-0.0.3-SNAPSHOT-jar-with-dependencies-06122017.jar?dl=0).

## Usage

It is necessary to load a dataset source by pressing `ctrl` + `o`.
Right now, you can load N5 and HDF5 files.

To use the highlight mode, it is necessary to load a label source and select it from the upper menu by pressing `ctrl` + `tab`.

There are three modes available:
* Navigation only:

* Highlights:

   In this mode, you can use `left click` to select any neuron on the 2d viewers. Use `shift` + `left click` to select more than one neuron.
   Every selected neuron will be highlighted on the 2d panels and its 3d shape will be generated.

* Merge and split:


Useful commands:

| Command                 | Description        |
| ----------------------- |:------------------:|
| `f` | switch full screen on/off for the viewer on focus |
| `ctrl` + `p` | snapshot of the 3d scene |
| `ctrl` + `o` | open the source loader window |
| `shift` + `o` | turn on/off the 2d panels on the 3d scene |
| `shift` + `z` | reset the position of the viewer |
| `shift` + `right click` (on a 3d mesh)| open a menu to "export mesh" and to "center ortho slices at the position" |


## Development

[![Join the chat at https://gitter.im/saalfeldlab/bigcat](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/saalfeldlab/bigcat?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
Collaborative volume annotation and segmentation with BigDataViewer

## Tests

### Dependencies
* Junit

### Run

To run all tests:
```
mvn clean test
```

