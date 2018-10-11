# BIGCAT (working title)

## Install

### Install Script

The `install` uses Maven to download and manage dependencies, compiles and installs BigCAT into the local Maven repository, and installs a start-script `bigcat` (tested on Linux and MacOS only, but can be reproduced on Windows with some manual tweaking).
```shell
./install $HOME/bin
```

Run the installed start-script
```shell
bigcat -i  <input_hdf_file> -l <label/dataset> -r <raw/dataset>
```

### Conda

BigCAT is available on conda:
```shell
conda install -c hanslovsky bigcat
```

Run instructions missing...

### Fat JAR 

To compile a "fat jar" with all dependencies included, run:

```shell
mvn clean compile assembly:single
```

Run it
```shell
java -Xmx16G -jar target/bigcat-<VERSION>-jar-with-dependencies.jar -i <input_hdf_file> -l <label/dataset> -r <raw/dataset>
```

## Documentation

Documentation is on the [Wiki](https://github.com/saalfeldlab/bigcat/wiki/BigCat-User-Interface).

## Development

[![Join the chat at https://gitter.im/saalfeldlab/bigcat](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/saalfeldlab/bigcat?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
Collaborative volume annotation and segmentation with BigDataViewer


