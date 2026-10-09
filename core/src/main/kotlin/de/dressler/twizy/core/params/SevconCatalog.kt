package de.dressler.twizy.core.params

import de.dressler.twizy.core.canopen.ObjectAddress

/** All known SEVCON Gen4 objects of the Twizy (generated from Twizy-SDO-List.ods). */
object SevconCatalog {
    val all: List<Parameter> = GeneratedCatalog.entries

    private val byAddress = all.associateBy { it.address }

    operator fun get(address: ObjectAddress): Parameter =
        byAddress[address] ?: throw NoSuchElementException("$address is not in the catalog")

    operator fun get(index: Int, subIndex: Int): Parameter = get(ObjectAddress(index, subIndex))

    fun find(address: ObjectAddress): Parameter? = byAddress[address]

    fun tagged(tag: String): List<Parameter> = all.filter { tag in it.tags }
}
