process FOLDMASON_EASYMSA {
    tag "$meta.id"
    label 'process_medium'

    conda "${moduleDir}/environment.yml"
    container "community.wave.seqera.io/library/foldmason_pigz:54849036d93c89ed"

    input:
    tuple val(meta) , path(pdbs)
    tuple val(meta2), path(tree)
    val(compress)

    output:
    tuple val(meta), path("${prefix}_3di.fa${compress ? '.gz' : ''}"), emit: msa_3di
    tuple val(meta), path("${prefix}.fa${compress ? '.gz' : ''}")    , emit: msa_aa
    path "versions.yml"                                              , emit: versions

    when:
    task.ext.when == null || task.ext.when

    script:
    def args = task.ext.args ?: ''
    prefix = task.ext.prefix ?: "${meta.id}"
    def options_tree = tree ? "--guide-tree $tree" : ""
    """
    foldmason easy-msa \\
        ${pdbs} \\
        ${prefix} \\
        tmp \\
        ${options_tree} \\
        $args \\
        --threads $task.cpus

    if ${compress}; then
        pigz -p ${task.cpus} ${prefix}_3di.fa
        pigz -p ${task.cpus} ${prefix}.fa
    fi

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        foldmason: \$(foldmason | grep "foldmason Version:" | cut -d":" -f 2 | awk '{\$1=\$1;print}')
        pigz: \$(echo \$(pigz --version 2>&1) | sed 's/^.*pigz\\w*//' ))
    END_VERSIONS
    """

    stub:
    def args = task.ext.args ?: ''
    prefix = task.ext.prefix ?: "${meta.id}"
    """
    echo ""  ${compress ? '| gzip' : ''} > ${prefix}_3di.fa${compress ? '.gz' : ''}
    echo ""  ${compress ? '| gzip' : ''} > ${prefix}.fa${compress ? '.gz' : ''}

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        foldmason: \$(foldmason | grep "foldmason Version:" | cut -d":" -f 2 | awk '{\$1=\$1;print}')
        pigz: \$(echo \$(pigz --version 2>&1) | sed 's/^.*pigz\\w*//' ))
    END_VERSIONS
    """
}
