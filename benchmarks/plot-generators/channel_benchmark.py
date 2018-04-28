import matplotlib.pyplot as plt
import numpy as np
import sys
import math
import csv
import os

filename = sys.argv[1]

contention_factor_key = 'Param: contentionFactor'
threads_key = 'Param: threads'
coroutines_key = 'Param: coroutines'
channel_key = 'Param: channelCreator'
score_key = 'Score'

with open(filename) as f:
    reader = csv.DictReader(f)
    data = [r for r in reader]


def all_values(data, key):
    values = []
    for row in data:
        x = row[key]
        if x not in values:
            values.append(x)
    return values


contention_factors = all_values(data, contention_factor_key)
threads = all_values(data, threads_key)
channels = all_values(data, channel_key)

# channels = list(map(lambda cc: cc.replace('_RENDEZVOUS', ''), channels))
channels.remove('KOVAL_RENDEZVOUS_SPIN_1')
channels.remove('KOVAL_RENDEZVOUS_SPIN_5')
channels.remove('KOVAL_RENDEZVOUS_SPIN_20')
channels.remove('KOVAL_RENDEZVOUS_SPIN_50')
channels.remove('KOVAL_RENDEZVOUS_SPIN_75')
channels.remove('KOVAL_RENDEZVOUS_SPIN_100')
channels.remove('KOVAL_RENDEZVOUS_SPIN_150')
channels.remove('KOVAL_RENDEZVOUS_SPIN_INF')

threads = [float(i) for i in threads]

totalPlots = len(contention_factors)

f, axarr = plt.subplots(2, int(math.ceil(totalPlots / 2.0)))
for iPlot in range(0, totalPlots):
    xPlot = iPlot / 2
    yPlot = iPlot % 2
    cont_fact = contention_factors[iPlot]
    for c in channels:
        scores = []
        for row in data:
            if (row[contention_factor_key] == cont_fact) & (row[channel_key] == c):
                scores.append(float(row[score_key]))
        axarr[xPlot, yPlot].plot(threads, scores, label=c.replace('_RENDEZVOUS', '') if iPlot == 0 else "")
    axarr[xPlot, yPlot].set_title('Channels = ' + str(cont_fact))
for ax in axarr.flat:
    ax.set(xlabel='Threads', ylabel='ms')
# Hide x labels and tick labels for top plots and y ticks for right plots.
for ax in axarr.flat:
    ax.label_outer()
f.legend(loc=9, ncol=4, prop={'size': 6})
# f.suptitle('Total time of a bulk of send/receive operations (less is better)')

# plt.show()
output_filename = os.path.splitext(filename)[0] + '.pdf'
f.savefig(output_filename, bbox_inches='tight', bbox_to_anchor=(0.5, -1))
