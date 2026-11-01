package org.documentknowledge.documentmanagement.domain;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ShareActionCompatibilityTest {
 @Test void onlyExplicitEleventhActionIsAddedWithoutRenamingExistingTen(){var original=Set.of("document.create","document.inspect-draft","document.read","document.update-metadata","document.acl.restrict","document.acl.delegate","document.version.append","document.legal-hold.place","document.legal-hold.inspect","document.content.review");var all=new HashSet<>(Arrays.stream(Action.values()).map(Action::wire).toList());assertTrue(all.remove("document.share-to-collection"));assertEquals(original,all);assertEquals(Action.SHARE_TO_COLLECTION,Action.parse("document.share-to-collection"));for(String invalid:List.of("document.share","document.share-to-collection ","DOCUMENT.SHARE-TO-COLLECTION","document.share-to-collection.*"))assertThrows(IllegalArgumentException.class,()->Action.parse(invalid));}
}
