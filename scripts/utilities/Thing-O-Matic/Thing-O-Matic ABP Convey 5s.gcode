(**** TOM Utility - Run Conveyor for 50s ****)
(This file is for a MakerBot Thing-O-Matic)
(For firmware pre-version 3.1, this will run)
(  whatever is connected to M106. From 3.1  )
(   onward, this runs the automated build   )
(   platform for 5 seconds.                )
(**** begin eject ****)
M73 P0
M106 (conveyor on)
G04 P5000 (wait t/1000 seconds)
M107 (conveyor off)
M73 P100
(**** end eject ****)
