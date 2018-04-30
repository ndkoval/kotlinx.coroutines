import matplotlib.pyplot as plt
from matplotlib.ticker import ScalarFormatter
import numpy as np
import sys
import math
import csv
import os
import itertools

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

channels.remove('ELIZAROV_RENDEZVOUS')
channels.remove('KOVAL_MS_RENDEZVOUS')
channels.remove('KOVAL_STACK_RENDEZVOUS')

channels.remove('KOVAL_RENDEZVOUS_SPIN_1')
channels.remove('KOVAL_RENDEZVOUS_SPIN_5')
channels.remove('KOVAL_RENDEZVOUS_SPIN_20')
channels.remove('KOVAL_RENDEZVOUS_SPIN_50')
channels.remove('KOVAL_RENDEZVOUS_SPIN_75')
channels.remove('KOVAL_RENDEZVOUS_SPIN_100')
channels.remove('KOVAL_RENDEZVOUS_SPIN_150')
channels.remove('KOVAL_RENDEZVOUS_SPIN_200')
channels.remove('KOVAL_RENDEZVOUS_SPIN_300') # best
channels.remove('KOVAL_RENDEZVOUS_SPIN_400')
channels.remove('KOVAL_RENDEZVOUS_SPIN_500')
channels.remove('KOVAL_RENDEZVOUS_SPIN_600')
channels.remove('KOVAL_RENDEZVOUS_SPIN_700')
channels.remove('KOVAL_RENDEZVOUS_SPIN_800')
channels.remove('KOVAL_RENDEZVOUS_SPIN_900')
channels.remove('KOVAL_RENDEZVOUS_SPIN_1000')
channels.remove('KOVAL_RENDEZVOUS_SPIN_1200')
channels.remove('KOVAL_RENDEZVOUS_SPIN_1500')
channels.remove('KOVAL_RENDEZVOUS_SPIN_5000')
channels.remove('KOVAL_RENDEZVOUS_SPIN_INF')

channels.remove('KOVAL_RENDEZVOUS_SPIN_700_NEW') # always suspend (failed experiment), best

channels.remove('KOVAL_RENDEZVOUS_SPIN_100_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_200_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_300_NEW_2') # best
channels.remove('KOVAL_RENDEZVOUS_SPIN_400_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_500_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_600_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_700_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_800_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_1000_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_1200_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_1500_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_2000_NEW_2')
channels.remove('KOVAL_RENDEZVOUS_SPIN_3000_NEW_2')

channels.remove('KOVAL_RENDEZVOUS_SPIN_300_NEW_2_CONTENDED_HT') # same
channels.remove('KOVAL_RENDEZVOUS_SPIN_300_NEW_2_CONTENDED_INDEXES') # bad

# channels.remove('KOVAL_RENDEZVOUS_SPIN_300_NEW_3') # Unsafe instead of AtomicReferenceArray, better

threads = [float(i) for i in threads]

totalPlots = len(contention_factors)
# for row in data:
#      row[score_key] = 1 / float(row[score_key])


def draw():
    yPlotMax = int(math.ceil(totalPlots / 2.0))
    f, axarr = plt.subplots(yPlotMax, 2 if totalPlots > 1 else 1, squeeze = False)
    for iPlot in range(0, totalPlots):
        marker = itertools.cycle((','))
        # marker = itertools.cycle((',', 's', 'v', '^', '*', 'p', '8'))

        yPlot = iPlot / 2
        xPlot = iPlot % 2
        ax = axarr[yPlot, xPlot]
        ax.set_xscale('log', basex=2)
        ax.xaxis.set_major_formatter(ScalarFormatter())
        ax.axvline(144, color='k', linestyle='dashed', linewidth=1)
        if iPlot == 0:
            ax.annotate('cores = threads',
                xy=(144, 500), fontSize=8, arrowprops=dict(arrowstyle='->'), xytext=(10, 150))
        cont_fact = contention_factors[iPlot]
        for c in channels:
            scores = []
            for row in data:
                if (row[contention_factor_key] == cont_fact) & (row[channel_key] == c):
                    scores.append(float(row[score_key]))
            ax.plot(threads, scores, marker = marker.next(), label=c.replace('_RENDEZVOUS', '') if iPlot == 0 else "")
            # ax.plot(threads, scores, label=c.replace('_RENDEZVOUS', '').replace('ELIZAROV', 'CURRENT').replace('KOVAL_SPIN_700_NEW', 'NEW') if iPlot == 0 else "")
        ax.set_title('Channels = ' + str(cont_fact))
        if xPlot == 0: ax.set(ylabel='ms/batch')
        if yPlot == yPlotMax - 1: ax.set(xlabel='threads')
    f.legend(loc=9, ncol=4, prop={'size': 6})
    # f.suptitle('Total time of a bulk of send/receive operations (less is better)')

    plt.show()
    f.subplots_adjust(hspace=.6, wspace=.4)
    output_filename = os.path.splitext(filename)[0] + '.pdf'
    f.savefig(output_filename, bbox_inches='tight', bbox_to_anchor=(0.5, -1))

# with plt.xkcd(): draw()
draw()
