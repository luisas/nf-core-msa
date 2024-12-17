@Grab('com.xlson.groovycsv:groovycsv:1.3')
import static com.xlson.groovycsv.CsvParser.parseCsv


/**
 * Saves a list of maps to a CSV file.
 *
 * @param data The list of maps to be saved. Each map represents a row in the CSV.
 * @param fileName The name of the file to save the CSV data to.
 */
def saveMapToCsv(List<Map> data, String fileName) {
    if (data.isEmpty()) {
        println "No data to write"
        return
    }

    // Extract headers from the keys of the first map
    def headers = data[0].keySet().join(',')
    
    // Generate CSV content by joining the values of each map with commas
    def csvContent = data.collect { row ->
        row.values().join(',')
    }.join('\n')

    // Write headers and CSV content to the specified file
    new File(fileName).withWriter { writer ->
        writer.write(headers + '\n' + csvContent)
    }
}


/**
 * Cleans the trace data by converting each row into a mutable map
 * and performing necessary transformations.
 * 
 * The following transformations are performed:
 * - Extract the tag from the 'name' column using a regex pattern
 * - Extract 'id' and 'args' from the tag
 * - Process the 'full_name' to extract workflow and process details 
 *
 * @param trace The trace data to be cleaned.
 * @return The cleaned trace data.
 */
def cleanTrace(ArrayList trace) {

    // Convert each row into a mutable map for dynamic property addition
    def cleanedTrace = trace.collect { row ->

        def mutableRow = new LinkedHashMap(row)

        // Extract the tag from the 'name' column using a regex pattern
        def tagMatch = (mutableRow.name =~ /\((.*)\)/)
        mutableRow.tag = tagMatch ? tagMatch[0][1] : null

        // Extract 'id' and 'args' from the tag safely
        mutableRow.id = mutableRow.tag?.tokenize(' ')?.first()
        mutableRow.args = mutableRow.tag?.split("args:")?.with { it.size() > 1 ? it[1].trim() : "default" }

        // Process the 'full_name' to extract workflow and process details
        mutableRow.full_name = mutableRow.name.split(/\(/)?.first()?.trim()
        def nameParts = mutableRow.full_name?.tokenize(':') ?: []
        mutableRow.process = nameParts ? nameParts.last() : null
        mutableRow.subworkflow = nameParts.size() > 1 ? nameParts[-2] : null

        // Replace "null" strings with actual null values
        mutableRow.each { key, value ->
            if (value == 'null') {
                mutableRow[key] = null
            }
        }

        // if args_tree is null, default
        if (mutableRow.args == null) {
            mutableRow.args = "default"
        }
        
        return mutableRow
    }

    // Return the cleaned trace
    return cleanedTrace.findAll { it != null }
}


/**
 * Utility function to convert time strings to minutes.
 *
 * This function takes a time string in the format of hours, minutes, seconds, and milliseconds,
 * and converts it to a total number of minutes.
 *
 * Example input formats:
 * - "1h 30m"
 * - "45m 30s"
 * - "2h 15m 10s 500ms"
 *
 * @param timeStr The time string to be converted.
 * @return The total time in minutes as a double.
 * @throws IllegalArgumentException if the time string is not in the correct format.
 */
 def convertTime(String timeStr) {
    def pattern = /((?<hours>\d+(\.\d+)?)h)?\s*((?<minutes>\d+(\.\d+)?)m)?\s*((?<seconds>\d+(\.\d+)?)s)?\s*((?<milliseconds>\d+(\.\d+)?)ms)?/
    def matcher = timeStr.trim() =~ pattern

    if (!matcher.matches()) {
        throw new IllegalArgumentException("Time string is not in the correct format: $timeStr")
    }

    def hours = matcher.group('hours')?.toDouble() ?: 0.0
    def minutes = matcher.group('minutes')?.toDouble() ?: 0.0
    def seconds = matcher.group('seconds')?.toDouble() ?: 0.0
    def milliseconds = matcher.group('milliseconds')?.toDouble() ?: 0.0

    return (hours * 60) + minutes + (seconds / 60) + (milliseconds / 60000)
}

/**
 * Utility function to convert memory to GB.
 *
 * This function takes a memory string with units (GB, MB, KB) and converts it to gigabytes (GB).
 *
 * Example input formats:
 * - "16GB"
 * - "2048MB"
 * - "1048576KB"
 *
 * @param memory The memory string to be converted.
 * @return The memory in gigabytes as a double, or null if the input is invalid.
 */
 def convertMemory(String memory) {
    if (!memory) return null

    if (memory.contains("GB")) {
        return memory.replace("GB", "").toDouble()
    } else if (memory.contains("MB")) {
        return memory.replace("MB", "").toDouble() / 1000
    } else if (memory.contains("KB")) {
        return memory.replace("KB", "").toDouble() / 1000000
    }
    return null
}


def prepTrace(trace, suffix_to_replace, subworkflow, keys) {

    // Extract the tree and align traces separately
    def trace_subworkflow = trace.findAll { it.subworkflow == subworkflow }

    // For each row, create a new row with the necessary keys and values
    trace_subworkflow.each { row ->
        def newRow = [:]
        
        // Clean the names (remove the unnecessary suffix)
        newRow.tree = row.process.replace(suffix_to_replace, "")

        def suffix = ""
        if(subworkflow == "ALIGN") {
            suffix = "_aligner"
            specific_key = "aligner"
        } else if(subworkflow == "COMPUTE_TREES") {
            suffix = "_tree"
            specific_key = "tree"
        }


        keys.each { key ->


            def newKey = key + suffix

            if (key in ['id', 'name', "tree", "aligner"]) {
                newKey = key
            }

            row[specific_key] = row.process.replace(suffix_to_replace, "")

            if ((key == 'realtime' || key == 'rss')) {
                newRow[newKey] = (key == 'realtime') ? convertTime(row[key]) : convertMemory(row[key])
            }else if(key == "args") {
                newRow[newKey+"_clean"] = row.args
            }else {
                newRow[newKey] = row[key]
            }
        }

        row.clear()
        row.putAll(newRow)
    }
    return trace_subworkflow
}



/**
 * Processes the latest trace file in the specified directory based on the given pattern.
 *
 * This function identifies and parses the latest trace file, filters lines related to evaluation,
 * and converts the trace data into CSV format.
 *
 * @param traceDirPath The path to the directory containing trace files.
 * @param filePattern The pattern to identify the trace files.
 * @return The parsed CSV data from the trace file.
 */
def latesTraceFileToCSV(String traceDirPath, String filePattern) {
    // Identify and parse the latest trace file based on the given pattern
    def traceFile = new File(traceDirPath).listFiles().findAll { it.name.startsWith(filePattern) }.sort { -it.lastModified() }.take(1)[0]

    // Keep only the lines that report running times related to evaluation
    def header = traceFile.readLines()[0].replaceAll("\t", ",")
    def traceFileAlign = traceFile.readLines().findAll { it.contains("COMPLETED") && it.contains("MULTIPLESEQUENCEALIGN:ALIGN") }.collect { it.replaceAll("\t", ",") }.join("\n")
    def trace = header + "\n" + traceFileAlign

    // Parse the trace data into CSV format
    def traceCsv = parseCsv(trace)
    
    return traceCsv
}


def keepKeysFromIterator(iterator, keysToKeep) {
    def modifiedData = iterator.collect { row ->
        def mutableRow = row.toMap().findAll { key, value ->
            keysToKeep.contains(key)
        }
        return mutableRow
    }
    // conver back to iterator
    modifiedData = modifiedData.collect { it as Map }
    return modifiedData
}


def mergeListsById(list1, list2, idKey) {

    def map1 = list1.collectEntries { [(it[idKey]): it] }

    def mergedList = list2.collect { row ->
        def id = row[idKey]
        def mergedRow = map1.containsKey(id) ? map1[id] + row : row
        return mergedRow
    }

    // convert back to iterator
    return mergedList
}

/**
 * Processes the latest trace file in the specified directory.
 *
 * This function identifies and parses the latest trace file based on the given pattern, filters lines related to evaluation,
 * cleans the trace data, and extracts tree and alignment traces separately.
 *
 * @param traceDirPath The path to the directory containing trace files.
 * @param filePattern The pattern to identify the trace files.
 * @return A map containing the tree traces and alignment traces.
 */
def processLatestTraceFile(String traceDirPath) {

    def traceCsv = latesTraceFileToCSV(traceDirPath, "execution_trace")
    def co2Csv = latesTraceFileToCSV(traceDirPath, "co2footprint_trace")

    co2Csv = keepKeysFromIterator(co2Csv, ["name", "energy_consumption", "CO2e", "powerdraw_cpu", "cpu_model", "requested_memory"])
    trace_co2_csv = mergeListsById(traceCsv.collect { it.toMap() }, co2Csv, "name")

    keys = ["id","name", "args", "tree", "aligner", "realtime", "%cpu", "rss", "peak_rss", "vmem", "peak_mem", "rchar", "wchar", "cpus", "energy_consumption", "CO2e", "powerdraw_cpu", "cpu_model", "requested_memory"]
    // Extract the information from the tag
    def cleanTraceData = cleanTrace(trace_co2_csv)    

    // Extract the tree and align traces separately
    def traceTrees = prepTrace(cleanTraceData, suffix_to_replace = "_GUIDETREE", subworkflow = "COMPUTE_TREES", keys)
    def traceAlign = prepTrace(cleanTraceData, suffix_to_replace = "_ALIGN", subworkflow = "ALIGN", keys)


    // Return the extracted traces as a map
    return [traceTrees: traceTrees, traceAlign: traceAlign]
}



def merge_summary_and_traces(summary_file, trace_dir_path, outFileName){

    // -------------------
    // TRACE FILE 
    // -------------------

    // 1. Identify and parse the latest trace file
    // 2. Clean the trace (only completed tasks, keep only needed columns)
    // 3. Extract tree and align traces separately
    def trace_file = processLatestTraceFile(trace_dir_path)
    
    // -------------------
    // SUMMARY FILE
    // -------------------  

    // Parse the summary data (scientific accuracy file: SP, TC etc.)  
    def data = parseCsv(new File(summary_file).readLines().collect { it.replaceAll("\t", ",") }.join("\n"))
    data = data.collect { row ->
        def mutableRow = row.toMap()
        return mutableRow
    }

    print("##############################################################")
    print("\n")
    print(trace_file)
    print("\n\n")
    // Merge the summary data with the trace data
    def mergedData = []
    data.each { row ->
         

        print("##### matching -----------------------------------------------------------")
        print("\n")
        print(row)
        print("\n")
        def treeMatch = trace_file.traceTrees.find { it.id == row.id && it.tree == row.tree && it.args_tree_clean == row.args_tree_clean }
        print(treeMatch)
        print("\n")
        def alignMatch = trace_file.traceAlign.find { it.id == row.id && it.aligner == row.aligner && it.args_aligner_clean == row.args_aligner_clean }
        print(alignMatch)
        print("\n")
        def mergedRow = row + (treeMatch ?: [:]) + (alignMatch ?: [:])
        mergedData << mergedRow
    }

    // Save the merged data to a file
    saveMapToCsv(mergedData, outFileName)

}

outdir = "/home/luisasantus/Desktop/multiplesequencealign/res2"

def summary_file = "${outdir}/summary/complete_summary_stats_eval.csv"
def outFileName = "${outdir}/../test_merged.csv"
def trace_dir_path = "${outdir}/pipeline_info/"
def co2_path = "${outdir}/pipeline_info/execution_trace_CO2"

merge_summary_and_traces(summary_file, trace_dir_path, outFileName)