         nam   SysGo
         ttl   os9 system module    

         ifp1
         use   os9defs
         use   scfdefs
         endc
tylg     set   Systm+Objct   
atrv     set   ReEnt+rev
rev      set   $01
edition  set   $05

hwclock  set   $FF10
         mod   eom,name,tylg,atrv,start,size
stack    rmb   200
size     equ   .
name     equ   *
         fcs   /SysGo/
         fcb   edition
BootMsg  fcc   "OS-9 LEVEL ONE VERSION 1.2"
         fcb   C$CR,C$LF
         fcb   C$LF
MsgEnd   equ   *
ExecDir  fcc   "Cmds"
         fcb   C$CR
         fcc   ",,,,,,,,,," room for patch
Shell    fcc   "Shell"
         fcb   C$CR
         fcc   ",,,,,,,,,," room for patch
Startup  fcc   "STARTUP -P"
         fcb   C$CR
         fcc   ",,,,,,,,,," room for patch
         
BasicRst fcb   $55
         fcb   $00
         fcb   $74
         fcb   $12
         fcb   $7F
         fcb   $FF
         fcb   $03
         fcb   $B7
         fcb   $FF
         fcb   $DF
         fcb   $7E
         fcb   $F0
         fcb   $02

* SysGo entry point
start    equ   *
         leax  >IcptRtn,pcr
         os9   F$Icpt   
         leax  >BasicRst,pcr  CC warmstart
         ldu   #D.CBStrt
         ldb   #start-BasicRst
CopyLoop lda   ,x+
         sta   ,u+
         decb  
         bne   CopyLoop
         leax  >BootMsg,pcr
         ldy   #MsgEnd-BootMsg
         lda   #$01
         os9   I$Write  

* OS9p2 has looked for a module called "Clock" and initialised it.
         ldx   #hwclock
         os9   F$STime

         leax  >ExecDir,pcr
         lda   #$04
         os9   I$ChgDir 

         leax  >Shell,pcr
         leau  >Startup,pcr
         ldd   #$0100
         ldy   #$0015
         os9   F$Fork   
         bcs   DeadEnd
         os9   F$Wait   
FrkShell leax  >Shell,pcr
         ldd   #$0100
         ldy   #$0000
         os9   F$Fork   
         bcs   DeadEnd
         os9   F$Wait   
         bcc   FrkShell
DeadEnd  bra   DeadEnd

* Intercept routine
IcptRtn  rti
         emod
eom      equ   *
