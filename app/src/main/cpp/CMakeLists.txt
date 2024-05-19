cmake_minimum_required(VERSION 3.18.1)


project("reed solomon")

find_library(log-lib log)

set(SRCLIST main.c rs.c fec84.c)
add_library(rs SHARED ${SRCLIST})
target_link_libraries(rs ${log-lib})
