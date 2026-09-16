import os, pty, sys
def read(fd): return os.read(fd, 1024) or sys.exit(0)
pty.spawn([sys.argv[1], "-i"], master_read=read, stdin_read=read)