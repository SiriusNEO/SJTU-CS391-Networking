for i in $(seq 1 5)
do 
    touch latency_L$(expr $i).txt;
    touch throughput_L$(expr $i).txt;
done