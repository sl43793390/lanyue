package com.sl.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cn.hutool.core.util.ArrayUtil;

public class Algorithm {

	public static void main(String[] args) {
		// TODO Auto-generated method stub

	}
	
	/**
	 * 1 冒泡排序
        每次循环都比较前后两个元素的大小，如果前者大于后者，则将两者进行交换。这样做会将每次循环中最大的元素替换到末尾，
        逐渐形成有序集合。将每次循环中的最大元素逐渐由队首转移到队尾的过程形似“冒泡”过程，故因此得名。

        一个优化冒泡排序的方法就是如果在一次循环的过程中没有发生交换，则可以立即退出当前循环，
        因为此时已经排好序了（也就是时间复杂度最好情况下是的由来）。
	 * @param array
	 * @return
	 */
	public static int[] bubbleSort(int[] array) {
	    if (array == null || array.length < 2) {
	        return array;
	    }
	 
	    for (int i = 0; i < array.length - 1; i++) {
	        boolean flag = false;
	        for (int j = 0; j < array.length - 1 - i; j++) {
	            if (array[j] > array[j + 1]) {
	                //这里交换两个数据并没有使用中间变量，而是使用异或的方式来实现
	                array[j] = array[j] ^ array[j + 1];
	                array[j + 1] = array[j] ^ array[j + 1];
	                array[j] = array[j] ^ array[j + 1];
	 
	                flag = true;
	            }
	        }
	        if (!flag) {
	            break;
	        }
	    }
	    return array;
	}
/**
 *  选择排序
        每次循环都会找出当前循环中最小的元素，然后和此次循环中的队首元素进行交换。
 * @param array
 * @return
 */
	public static int[] selectSort(int[] array) {
	    if (array == null || array.length < 2) {
	        return array;
	    }
	 
	    for (int i = 0; i < array.length - 1; i++) {
	        int minIndex = i;
	        for (int j = i + 1; j < array.length; j++) {
	            if (array[j] < array[minIndex]) {
	                minIndex = j;
	            }
	        }
	        if (minIndex > i) {
	            array[i] = array[i] ^ array[minIndex];
	            array[minIndex] = array[i] ^ array[minIndex];
	            array[i] = array[i] ^ array[minIndex];
	        }
	    }
	    return array;
	}
	
	/**
	 * 插入排序
        插入排序的精髓在于每次都会在先前排好序的子集合中插入下一个待排序的元素，
        每次都会判断待排序元素的上一个元素是否大于待排序元素，如果大于，则将元素右移，
        然后判断再上一个元素与待排序元素...以此类推。直到小于等于比较元素时就是找到了该元素的插入位置。
        这里的等于条件放在哪里很重要，因为它是决定插入排序稳定与否的关键。

	 * @param array
	 * @return
	 */
	public static Integer[] insertSort(Integer[] array) {
	    if (array == null || array.length < 2) {
	        return array;
	    }
	 
	    for (int i = 1; i < array.length; i++) {
	        int temp = array[i];
	        int j = i - 1;
	        while (j >= 0 && array[j] > temp) {
	            array[j + 1] = array[j];
	            j--;
	        }
	        array[j + 1] = temp;
	    }
	    return array;
	}
	/**
	 * 希尔排序
        希尔排序可以认为是插入排序的改进版本。首先按照初始增量来将数组分成多个组，每个组内部使用插入排序。
        然后缩小增量来重新分组，组内再次使用插入排序...重复以上步骤，直到增量变为1的时候，这个时候整个数组就是一个分组，
        进行最后一次完整的插入排序即可结束。

        在排序开始时的增量较大，分组也会较多，但是每个分组中的数据较少，所以插入排序会很快。随着每一轮排序的进行，
        增量和分组数会逐渐变小，每个分组中的数据会逐渐变多。但因为之前已经经过了多轮的分组排序，
        而此时的数组会趋近于一个有序的状态，所以这个时候的排序也是很快的。而对于数据较多且趋向于无序的数据来说，
        如果只是使用插入排序的话效率就并不高。所以总体来说，希尔排序的执行效率是要比插入排序高的。

	 * @param array
	 * @return
	 */
	public static int[] shellSort(int[] array) {
	    if (array == null || array.length < 2) {
	        return array;
	    }
	 
	    int gap = array.length >>> 1;
	    while (gap > 0) {
	        for (int i = gap; i < array.length; i++) {
	            int temp = array[i];
	            int j = i - gap;
	            while (j >= 0 && array[j] > temp) {
	                array[j + gap] = array[j];
	                j = j - gap;
	            }
	            array[j + gap] = temp;
	        }
	        gap >>>= 1;
	    }
	    return array;
	}
	
	/**
	 * 归并排序
        归并排序使用的是分治的思想，首先将数组不断拆分，直到最后拆分成两个元素的子数组，
        将这两个元素进行排序合并，再向上递归。不断重复这个拆分和合并的递归过程，最后得到的就是排好序的结果。
        合并的过程是将两个指针指向两个子数组的首位元素，两个元素进行比较，较小的插入到一个temp数组中，
        同时将该数组的指针右移一位，继续比较该数组的第二个元素和另一个元素…重复这个过程。这样temp数组保存
        的便是这两个子数组排好序的结果。最后将temp数组复制回原数组的位置处即可。

	 * @param array
	 * @return
	 */
	public static int[] mergeSort(int[] array) {
	    if (array == null || array.length < 2) {
	        return array;
	    }
	 
	    return mergeSort(array, 0, array.length - 1);
	}
	 
	private static int[] mergeSort(int[] array, int left, int right) {
	    if (left < right) {
	        //这里没有选择“(left + right) / 2”的方式，是为了防止数据溢出
	        int mid = left + ((right - left) >>> 1);
	        // 拆分子数组
	        mergeSort(array, left, mid);
	        mergeSort(array, mid + 1, right);
	        // 对子数组进行合并
	        merge(array, left, mid, right);
	    }
	    return array;
	}
	 
	private static void merge(int[] array, int left, int mid, int right) {
	    int[] temp = new int[right - left + 1];
	    // p1和p2为需要对比的两个数组的指针，k为存放temp数组的指针
	    int p1 = left, p2 = mid + 1, k = 0;
	    while (p1 <= mid && p2 <= right) {
	        if (array[p1] <= array[p2]) {
	            temp[k++] = array[p1++];
	        } else {
	            temp[k++] = array[p2++];
	        }
	    }
	    // 把剩余的数组直接放到temp数组中
	    while (p1 <= mid) {
	        temp[k++] = array[p1++];
	    }
	    while (p2 <= right) {
	        temp[k++] = array[p2++];
	    }
	    // 复制回原数组
	    for (int i = 0; i < temp.length; i++) {
	        array[i + left] = temp[i];
	    }
	}
	
	/**
	 * 快速排序
        快速排序的核心是要有一个基准数据temp，一般取数组的第一个位置元素。然后需要有两个指针left和right，
        分别指向数组的第一个和最后一个元素。
        首先从right开始，比较right位置元素和基准数据。如果大于等于，则将right指针左移，比较下一位元素；如果小于，
        就将right指针处数据赋给left指针处（此时left指针处数据已保存进temp中），left指针+1，之后开始比较left指针处数据。
        拿left位置元素和基准数据进行比较。如果小于等于，则将left指针右移，比较下一位元素；而如果大于就将left指针处
        数据赋给right指针处，right指针-1，之后开始比较right指针处数据…重复这个过程。
        直到left和right指针相等时，说明这一次比较过程完成。此时将先前存放进temp中的基准数据赋值给当前left和right
        指针共同指向的位置处，即可完成这一次排序操作。
        之后递归排序基础数据的左半部分和右半部分，递归的过程和上面讲述的过程是一样的，只不过数组范围不再是原来的全部数组了，
        而是现在的左半部分或右半部分。当全部的递归过程结束后，最终结果即为排好序的结果
        
               正如上面所说的，一般取第一个元素作为基准数据，但如果当前数据为从大到小排列好的数据，而现在要按从小到大的顺序排列，
               则数据分摊不均匀，时间复杂度会退化为，而不是正常情况下的。此时采取一个优化手段，即取最左边、最右边和最中间的三个
               元素的中间值作为基准数据，以此来避免时间复杂度为的情况出现，当然也可以选择更多的锚点或者随机选择的方式来进行选取。

        还有一个优化的方法是：像快速排序、归并排序这样的复杂排序方法在数据量大的情况下是比选择排序、冒泡排序和插入排序的
        效率要高的，但是在数据量小的情况下反而要更慢。所以我们可以选定一个阈值，这里选择为47（和源码中使用的一样）。
        当需要排序的数据量小于47时走插入排序，大于47则走快速排序。

	 */
	private static final int THRESHOLD = 47;
	 
	public static Integer[] quickSort(Integer[] integers) {
	    if (integers == null || integers.length < 2) {
	        return integers;
	    }
	 
	    return quickSort(integers, 0, integers.length - 1);
	}
	 
	private static Integer[] quickSort(Integer[] array, int start, int end) {
	    // 如果当前需要排序的数据量小于等于THRESHOLD则走插入排序的逻辑，否则继续走快速排序
	    if (end - start <= THRESHOLD - 1) {
	        return insertSort(array);
	    }
	 
	    // left和right指针分别指向array的第一个和最后一个元素
	    int left = start, right = end;
	 
	    /*
	    取最左边、最右边和最中间的三个元素的中间值作为基准数据，以此来尽量避免每次都取第一个值作为基准数据、
	    时间复杂度可能退化为O(n^2)的情况出现
	     */
	    int middleOf3Indexs = middleOf3Indexs(array, start, end);
	    if (middleOf3Indexs != start) {
	        swap(array, middleOf3Indexs, start);
	    }
	 
	    // temp存放的是array中需要比较的基准数据
	    int temp = array[start];
	 
	    while (left < right) {
	        // 首先从right指针开始比较，如果right指针位置处数据大于temp，则将right指针左移
	        while (left < right && array[right] >= temp) {
	            right--;
	        }
	        // 如果找到一个right指针位置处数据小于temp，则将right指针处数据赋给left指针处
	        if (left < right) {
	            array[left++] = array[right];
	        }
	        // 然后从left指针开始比较，如果left指针位置处数据小于temp，则将left指针右移
	        while (left < right && array[left] <= temp) {
	            left++;
	        }
	        // 如果找到一个left指针位置处数据大于temp，则将left指针处数据赋给right指针处
	        if (left < right) {
	            array[right--] = array[left];
	        }
	    }
	    // 当left和right指针相等时，此时循环跳出，将之前存放的基准数据赋给当前两个指针共同指向的数据处
	    array[left] = temp;
	    // 一次替换后，递归交换基准数据左边的数据
	    if (start < left - 1) {
	        array = quickSort(array, start, left - 1);
	    }
	    // 之后递归交换基准数据右边的数据
	    if (right + 1 < end) {
	        array = quickSort(array, right + 1, end);
	    }
	    return array;
	}
	 
	private static int middleOf3Indexs(Integer[] array, int start, int end) {
	    int mid = start + ((end - start) >>> 1);
	    if (array[start] < array[mid]) {
	        if (array[mid] < array[end]) {
	            return mid;
	        } else {
	            return array[start] < array[end] ? end : start;
	        }
	    } else {
	        if (array[mid] > array[end]) {
	            return mid;
	        } else {
	            return array[start] < array[end] ? start : end;
	        }
	    }
	}
	 
	private static void swap(Integer[] array, Integer i, int j) {
	    array[i] = array[i] ^ array[j];
	    array[j] = array[i] ^ array[j];
	    array[i] = array[i] ^ array[j];
	}
	
	/**
	 * 桶排序
        上面的计数排序在数组最大值和最小值之间的差值是多少，就会生成一个多大的临时数组，也就是生成了一个这么多的桶，
        而每个桶中就只插入一个数据。如果差值比较大的话，会比较浪费空间。那么我能不能在一个桶中插入多个数据呢？当然可以，
        而这就是桶排序的思路。桶排序类似于哈希表，通过一定的映射规则将数组中的元素映射到不同的桶中，每个桶内进行内部排序，
        最后将每个桶按顺序输出就行了。桶排序执行的高效与否和是否是稳定的取决于哈希散列的算法以及内部排序的结果。需要注意的是，
        这个映射算法并不是常规的映射算法，要求是每个桶中的所有数都要比前一个桶中的所有数都要大，这样最后输出的才是一个排好序的结果。
        比如说第一个桶中存1-30的数字，第二个桶中存31-60的数字，第三个桶中存61-90的数字...以此类推。下面给出一种实现：

	 * @param array
	 * @return
	 */
	public static int[] bucketSort(int[] array) {
	    if (array == null || array.length < 2) {
	        return array;
	    }
	 
	    //记录待排序数组中的最大值
	    int max = array[0];
	    //记录待排序数组中的最小值
	    int min = array[0];
	    for (int i : array) {
	        if (i > max) {
	            max = i;
	        }
	        if (i < min) {
	            min = i;
	        }
	    }
	    //计算桶的数量（可以自定义实现）
	    int bucketNumber = (max - min) / array.length + 1;
	    List<Integer>[] buckets = new ArrayList[bucketNumber];
	    //计算每个桶存数的范围（可以自定义实现或者不用实现）
	    int bucketRange = (max - min + 1) / bucketNumber;
	 
	    for (int value : array) {
	        //计算应该放到哪个桶中（可以自定义实现）
	        int bucketIndex = (value - min) / (bucketRange + 1);
	        //延迟初始化
	        if (buckets[bucketIndex] == null) {
	            buckets[bucketIndex] = new ArrayList<>();
	        }
	        //放入指定的桶
	        buckets[bucketIndex].add(value);
	    }
	    int index = 0;
	    for (List<Integer> bucket : buckets) {
	        //对每个桶进行内部排序，我这里使用的是快速排序，也可以使用别的排序算法，当然也可以继续递归去做桶排序
	        quickSort(ArrayUtil.toArray(bucket, Integer.class));
	        if (bucket == null) {
	            continue;
	        }
	        //将不为null的桶中的数据按顺序写回到array数组中
	        for (Integer integer : bucket) {
	            array[index++] = integer;
	        }
	    }
	    return array;
	}
	
	
	
}
/**
 *  堆排序
        堆排序的过程是首先构建一个大顶堆，大顶堆首先是一棵完全二叉树，其次它保证堆中某个节点的值总是不大于其父节点的值。
        因为大顶堆中的最大元素肯定是根节点，所以每次取出根节点即为当前大顶堆中的最大元素，取出后剩下的节点再重新构建大顶堆，
        再取出根节点，再重新构建…重复这个过程，直到数据都被取出，最后取出的结果即为排好序的结果。
   上面的经典实现中，如果需要变动节点时，都会来一次父子节点的互相交换操作（包括删除节点时首先做的要删除节点和最后
   一个节点之间的交换操作也是如此）。如果仔细思考的话，就会发现这其实是多余的。在需要交换节点的时候，只需要siftUp
   操作时的父节点或siftDown时的孩子节点重新移到当前需要比较的节点位置上，而比较节点是不需要移动到它们的位置上的。
   此时直接进入到下一次的判断中，重复siftUp或siftDown过程，直到最后找到了比较节点的插入位置后，才会将其插入进去。
   这样做的好处是可以省去一半的节点赋值的操作，提高了执行的效率。同时这也就意味着，需要将要比较的节点作为参数保存起来，
   而在ScheduledThreadPoolExecutor源码中也正是这么实现的

 * @author Administrator
 *
 */
 class MaxHeap {
	 
    /**
     * 排序数组
     */
    private int[] nodeArray;
    /**
     * 数组的真实大小
     */
    private int size;
 
    private int parent(int index) {
        return (index - 1) >>> 1;
    }
 
    private int leftChild(int index) {
        return (index << 1) + 1;
    }
 
    private int rightChild(int index) {
        return (index << 1) + 2;
    }
 
    private void swap(int i, int j) {
        nodeArray[i] = nodeArray[i] ^ nodeArray[j];
        nodeArray[j] = nodeArray[i] ^ nodeArray[j];
        nodeArray[i] = nodeArray[i] ^ nodeArray[j];
    }
 
    private void siftUp(int index) {
        //如果index处节点的值大于其父节点的值，则交换两个节点值，同时将index指向其父节点，继续向上循环判断
        while (index > 0 && nodeArray[index] > nodeArray[parent(index)]) {
            swap(index, parent(index));
            index = parent(index);
        }
    }
 
    private void siftDown(int index) {
        //左孩子的索引比size小，意味着索引index处的节点有左孩子，证明此时index节点不是叶子节点
        while (leftChild(index) < size) {
            //maxIndex记录的是index节点左右孩子中最大值的索引
            int maxIndex = leftChild(index);
            //右孩子的索引小于size意味着index节点含有右孩子
            if (rightChild(index) < size && nodeArray[rightChild(index)] > nodeArray[maxIndex]) {
                maxIndex = rightChild(index);
            }
            //如果index节点值比左右孩子值都大，则终止循环
            if (nodeArray[index] >= nodeArray[maxIndex]) {
                break;
            }
            //否则进行交换，将index指向其交换的左孩子或右孩子，继续向下循环，直到叶子节点
            swap(index, maxIndex);
            index = maxIndex;
        }
    }
 
    private void add(int value) {
        nodeArray[size++] = value;
        //构建大顶堆
        siftUp(size - 1);
    }
 
    private void extractMax() {
        /*
        将堆顶元素和最后一个元素进行交换
        此时并没有删除元素，而只是将size-1，剩下的元素重新构建成大顶堆
         */
        swap(0, --size);
        //重新构建大顶堆
        siftDown(0);
    }
 
    public int[] heapSort(int[] array) {
        if (array == null || array.length < 2) {
            return array;
        }
 
        nodeArray = new int[array.length];
        for (int value : array) {
            add(value);
        }
        for (int ignored : array) {
            extractMax();
        }
        return nodeArray;
    }
}
