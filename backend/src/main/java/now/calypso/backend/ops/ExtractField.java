package now.calypso.backend.ops;

import org.apache.thrift.TBase;

import com.rpl.rama.ops.RamaFunction1;

import static now.calypso.backend.CalypsoHelpers.getTFieldByName;

public class ExtractField implements RamaFunction1<TBase, Object> {
  String _fieldName;

  public ExtractField(String name) {
    _fieldName = name;
  }

  @Override
  public Object invoke(TBase obj) {
    return getTFieldByName(obj, _fieldName);
  }
}
