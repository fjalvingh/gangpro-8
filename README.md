# Gangpro-8 vintage EPROM Programmer software

![The Gangpro-8 programmer from Logical Devices](/blobs/gangpro.jpg)

The Gangpro-8 is a vintage (around 1981) gang programmer made by [Logical Devices](http://www.logicaldevices.com/). It
allows copying a single master (E)(P)ROM to one to eight others. The software for this device could not be found so
I wrote something simple to be able to up- and download ROM images to/from the device.

The device connects to a PC using a RS232 serial port. For this the device has a DB25 male connector. You
will need a straight cable to the PC, and probably an USB-Serial converter (the real deal, not a 5v one).  
Make sure that the RTS and CTS signals are implemented correctly in the cable.
The device's serial speed defaults to 1200bps, so data transfer is a bit slow.

The program is written in Java, and needs at least Java 11 to operate. It has
been tested and written on Linux, but it should work on Windows too.
Install Java, then run the program from the command line:

    java -jar gangpro8.jar [options]

## Downloading ROMs to the PC

To download the content of a ROM from the device you would enter:

    java -jar gangpro8.jar --serial /dev/ttyUSB0 --download test.bin 

This would show the instructions to execute on the Gangpro-8 (the version I have cannot be "commanded" to  
start an upload). The general process is as follows:

* Use the "Device" key to set the correct type.
* Enter the (EP)ROM in the MASTER socket of the Gangpro-8.
* Start the software
* Press the "setup" key until you see "upload" (9 times)

This should cause the software to see the upload.

## Uploading software to the Programmer

This will be implemented soon ;)


## Other usage details

### Output/Input formats

The program defaults to writing and reading binary files. By adding the
option --format intel you can specify that it needs to read/write Intel Hex
format instead.

### Serial port bps rate (baud rate)

Presumably the bit rate of the Gangpro-8 can be changed. It supports 1200, 2400 and 4800 bps.  
You change the baud rate as follows:

* Press and hold SELFTEST
* Press and hold VERIFY
* Release SELFTEST
* Release VERIFY
* Every press on VERIFY selects another bit rate (shown on its display).
* To select press the RESET button.

I could not test this (my device does not react to the RESET button), but the
software allows changing the bit rate by adding the --bps parameter:

    --bps 4800

## Acknowledgements

Many thanks to David from [Logical Devices](http://www.logicaldevices.com/) for spending time finding the documentation
for this old device.

This software uses the excellent [jSerialComms](https://github.com/Fazecast/jSerialComm) library. Thanks to  
the authors for this well documented and well written library!

