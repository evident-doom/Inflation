package inflation.ecology

/**
 * Something that passed between two bands, from the one that acted ([from], a group's id) to the other ([to]), as the
 * viewer is told of it ([HexGroups.dealt]). Nothing in the model reads these.
 */
class Dealing(val kind: Kind, val from: Int, val to: Int) {
    /** What passed. */
    enum class Kind(val label: String) {
        MET("met"), FOOD("gave food to"), ORNAMENTS("gave ornaments to"), GOODS("gave stone, salt, ochre or tools to"),
        FEAST("feasted"), FED_KIN("fed hungry kin"), PARTNERS("exchanged partners with"), TOLD("told of country"),
        RITE("met at a rite with"), RAID("raided"), DEFENDED("stood against raiders with"),
    }
}
